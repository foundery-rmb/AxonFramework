/*
 * Copyright (c) 2010-2014. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.commandhandling.disruptor;

import com.lmax.disruptor.EventHandler;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.RollbackConfiguration;
import org.axonframework.eventstore.EventStore;
import org.axonframework.messaging.unitofwork.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import static java.lang.String.format;

/**
 * Component of the DisruptorCommandBus that stores and publishes events generated by the command's execution.
 *
 * @author Allard Buijze
 * @since 2.0
 */
public class EventPublisher implements EventHandler<CommandHandlingEntry> {

    private static final Logger logger = LoggerFactory.getLogger(DisruptorCommandBus.class);

    private final EventStore eventStore;
    private final Executor executor;
    private final RollbackConfiguration rollbackConfiguration;
    private final int segmentId;
    private final Set<Object> blackListedAggregates = new HashSet<>();
    private final TransactionManager transactionManager;

    /**
     * Initializes the EventPublisher to publish Events to the given <code>eventStore</code> and <code>eventBus</code>
     * for aggregate of given <code>aggregateType</code>.
     *
     * @param executor              The executor which schedules response reporting
     * @param transactionManager    The transaction manager that manages the transaction around event storage and
     *                              publication
     * @param rollbackConfiguration The configuration that indicates which exceptions should result in a UnitOfWork
     * @param segmentId             The ID of the segment this publisher should handle
     */
    public EventPublisher(EventStore eventStore, Executor executor, TransactionManager transactionManager,
                          RollbackConfiguration rollbackConfiguration, int segmentId) {
        this.eventStore = eventStore;
        this.executor = executor;
        this.transactionManager = transactionManager;
        this.rollbackConfiguration = rollbackConfiguration;
        this.segmentId = segmentId;
    }

    @SuppressWarnings({"unchecked", "ThrowableResultOfMethodCallIgnored"})
    @Override
    public void onEvent(CommandHandlingEntry entry, long sequence, boolean endOfBatch) throws Exception {
        if (entry.isRecoverEntry()) {
            recoverAggregate(entry);
        } else if (entry.getPublisherId() == segmentId) {
            entry.unpauze();
            entry.onPrepareCommit(u -> eventStore.appendEvents(entry.getMessagesToPublish()));
            String aggregateIdentifier = entry.getAggregateIdentifier();
            if (aggregateIdentifier != null && blackListedAggregates.contains(aggregateIdentifier)) {
                rejectExecution(entry, aggregateIdentifier);
            } else {
                processPublication(entry, entry, aggregateIdentifier);
            }
        }
    }

    private void recoverAggregate(CommandHandlingEntry entry) {
        if (blackListedAggregates.remove(entry.getAggregateIdentifier())) {
            logger.info("Reset notification for {} received. The aggregate is removed from the blacklist",
                        entry.getAggregateIdentifier());
        }
    }

    @SuppressWarnings("unchecked")
    private void rejectExecution(CommandHandlingEntry entry, String aggregateIdentifier) {
        executor.execute(new ReportResultTask(entry.getCommand(),
                                              entry.getCallback(), null,
                                              new AggregateStateCorruptedException(
                                                      aggregateIdentifier,
                                                      format("Aggregate %s has been blacklisted and will be ignored "
                                                                     + "until its state has been recovered.",
                                                             aggregateIdentifier))));
        entry.rollback(entry.getExceptionResult());
    }

    @SuppressWarnings("unchecked")
    private void processPublication(CommandHandlingEntry entry, DisruptorUnitOfWork unitOfWork,
                                    String aggregateIdentifier) {
        invokeInterceptorChain(entry);
        Throwable exceptionResult = entry.getExceptionResult();
        if (exceptionResult != null && rollbackConfiguration.rollBackOn(exceptionResult)) {
            exceptionResult = performRollback(unitOfWork, entry.getAggregateIdentifier(), exceptionResult);
        } else {
            exceptionResult = performCommit(unitOfWork, exceptionResult, aggregateIdentifier);
        }
        if (exceptionResult != null || entry.getCallback().hasDelegate()) {
            executor.execute(new ReportResultTask(entry.getCommand(), entry.getCallback(),
                                                  entry.getResult(), exceptionResult));
        }
    }

    private void invokeInterceptorChain(CommandHandlingEntry entry) {
        try {
            entry.setResult(entry.getPublisherInterceptorChain().proceed(entry.getCommand()));
        } catch (Throwable throwable) {
            entry.setExceptionResult(throwable);
        }
    }

    private Throwable performRollback(DisruptorUnitOfWork unitOfWork, String aggregateIdentifier,
                                      Throwable exceptionResult) {
        unitOfWork.rollback(exceptionResult);
        if (aggregateIdentifier != null) {
            exceptionResult = notifyBlacklisted(unitOfWork, aggregateIdentifier, exceptionResult);
        }
        return exceptionResult;
    }

    @SuppressWarnings("unchecked")
    private Throwable performCommit(DisruptorUnitOfWork unitOfWork,
                                    Throwable exceptionResult, String aggregateIdentifier) {
        try {
            if (exceptionResult != null && rollbackConfiguration.rollBackOn(exceptionResult)) {
                unitOfWork.rollback(exceptionResult);
            } else {
                if (transactionManager != null) {
                    final Object transaction = transactionManager.startTransaction();
                    unitOfWork.onCommit(u -> transactionManager.commitTransaction(transaction));
                    unitOfWork.onRollback((u, e) -> transactionManager.rollbackTransaction(transaction));
                }
                unitOfWork.commit();
            }
        } catch (Exception e) {
            if (unitOfWork.isActive()) {
                // probably the transaction failed. Unit of Work needs to be rolled back.
                unitOfWork.rollback(e);
            }
            if (aggregateIdentifier != null) {
                exceptionResult = notifyBlacklisted(unitOfWork, aggregateIdentifier, e);
            } else {
                exceptionResult = e;
            }
        }
        return exceptionResult;
    }

    private Throwable notifyBlacklisted(DisruptorUnitOfWork unitOfWork, String aggregateIdentifier,
                                        Throwable cause) {
        Throwable exceptionResult;
        blackListedAggregates.add(aggregateIdentifier);
        exceptionResult = new AggregateBlacklistedException(
                aggregateIdentifier,
                format("Aggregate %s state corrupted. "
                               + "Blacklisting the aggregate until a reset message has been received",
                       aggregateIdentifier), cause);
        if (unitOfWork.isActive()) {
            unitOfWork.rollback(exceptionResult);
        }
        return exceptionResult;
    }

    private static class ReportResultTask<C, R> implements Runnable {

        private final CommandMessage<C> commandMessage;
        private final CommandCallback<C, R> callback;
        private final R result;
        private final Throwable exceptionResult;

        public ReportResultTask(CommandMessage<C> commandMessage, CommandCallback<C, R> callback,
                                R result, Throwable exceptionResult) {
            this.commandMessage = commandMessage;
            this.callback = callback;
            this.result = result;
            this.exceptionResult = exceptionResult;
        }

        @Override
        public void run() {
            if (exceptionResult != null) {
                callback.onFailure(commandMessage, exceptionResult);
            } else {
                callback.onSuccess(commandMessage, result);
            }
        }
    }
}
