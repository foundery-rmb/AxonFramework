/*
 * Copyright (c) 2010-2012. Axon Framework
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

package org.axonframework.integrationtests.loopbacktest.synchronous;

import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.SimpleCommandBus;
import org.axonframework.commandhandling.annotation.AnnotationCommandHandlerAdapter;
import org.axonframework.commandhandling.annotation.CommandHandler;
import org.axonframework.commandhandling.callbacks.VoidCallback;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.eventhandling.EventListener;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.SimpleCluster;
import org.axonframework.eventhandling.SimpleEventBus;
import org.axonframework.eventsourcing.DomainEventMessage;
import org.axonframework.eventsourcing.DomainEventStream;
import org.axonframework.eventsourcing.EventSourcingRepository;
import org.axonframework.eventsourcing.GenericDomainEventMessage;
import org.axonframework.eventsourcing.SimpleDomainEventStream;
import org.axonframework.eventsourcing.annotation.AbstractAnnotatedAggregateRoot;
import org.axonframework.eventsourcing.annotation.AggregateIdentifier;
import org.axonframework.eventsourcing.annotation.EventSourcingHandler;
import org.axonframework.eventstore.EventStore;
import org.axonframework.repository.AggregateNotFoundException;
import org.axonframework.repository.LockManager;
import org.axonframework.repository.OptimisticLockManager;
import org.axonframework.repository.PessimisticLockManager;
import org.axonframework.repository.Repository;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.axonframework.commandhandling.GenericCommandMessage.asCommandMessage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for issue #119
 *
 * @author Allard Buijze
 */
public class SynchronousLoopbackTest {

    private CommandBus commandBus;
    private EventBus eventBus;
    private String aggregateIdentifier;
    private EventStore eventStore;
    private VoidCallback reportErrorCallback;
    private CommandCallback<Object, Object> expectErrorCallback;

    @Before
    public void setUp() {
        aggregateIdentifier = UUID.randomUUID().toString();
        commandBus = new SimpleCommandBus();
        eventBus = new SimpleEventBus();
        eventStore = spy(new InMemoryEventStore());
        eventStore.appendEvents(Collections.singletonList(
                new GenericDomainEventMessage<>(aggregateIdentifier, 0,
                        new AggregateCreatedEvent(aggregateIdentifier),
                        null
                )));
        reset(eventStore);

        reportErrorCallback = new VoidCallback<Object>() {
            @Override
            protected void onSuccess(CommandMessage<?> commandMessage) {
            }

            @Override
            public void onFailure(CommandMessage commandMessage, Throwable cause) {
                throw new RuntimeException("Failure", cause);
            }
        };
        expectErrorCallback = new CommandCallback<Object, Object>() {
            @Override
            public void onSuccess(CommandMessage<?> commandMessage, Object result) {
                fail("Expected this command to fail");
            }

            @Override
            public void onFailure(CommandMessage<?> commandMessage, Throwable cause) {
                assertEquals("Mock exception", cause.getMessage());
            }
        };
    }

    protected void initializeRepository(LockManager lockingStrategy) {
        EventSourcingRepository<CountingAggregate> repository = new EventSourcingRepository<>(
                CountingAggregate.class, eventStore,
                lockingStrategy);
        repository.setEventBus(eventBus);
        AnnotationCommandHandlerAdapter.subscribe(new CounterCommandHandler(repository), commandBus);
    }

    @Test
    public void testLoopBackKeepsProperEventOrder_PessimisticLocking() {
        initializeRepository(new PessimisticLockManager());
        EventListener el = event -> {
            DomainEventMessage domainEvent = (DomainEventMessage) event;
            if (event.getPayload() instanceof CounterChangedEvent) {
                CounterChangedEvent counterChangedEvent = (CounterChangedEvent) event.getPayload();
                if (counterChangedEvent.getCounter() == 1) {
                    commandBus.dispatch(asCommandMessage(
                            new ChangeCounterCommand(domainEvent.getAggregateIdentifier(),
                                                     counterChangedEvent.getCounter() + 1)), reportErrorCallback);
                    commandBus.dispatch(asCommandMessage(
                            new ChangeCounterCommand(domainEvent.getAggregateIdentifier(),
                                                     counterChangedEvent.getCounter() + 2)), reportErrorCallback);
                }
            }
        };
        eventBus.subscribe(new SimpleCluster("test", el));

        commandBus.dispatch(asCommandMessage(new ChangeCounterCommand(aggregateIdentifier, 1)), reportErrorCallback);

        DomainEventStream storedEvents = eventStore.readEvents(aggregateIdentifier);
        assertTrue(storedEvents.hasNext());
        while (storedEvents.hasNext()) {
            DomainEventMessage next = storedEvents.next();
            if (next.getPayload() instanceof CounterChangedEvent) {
                CounterChangedEvent event = (CounterChangedEvent) next.getPayload();
                assertEquals(event.getCounter(), next.getSequenceNumber());
            }
        }

        verify(eventStore, times(3)).appendEvents(anyEventList());
    }

    @Test
    public void testLoopBackKeepsProperEventOrder_OptimisticLocking() throws Throwable {
        initializeRepository(new OptimisticLockManager());
        EventListener el = event -> {
            DomainEventMessage domainEvent = (DomainEventMessage) event;
            if (event.getPayload() instanceof CounterChangedEvent) {
                CounterChangedEvent counterChangedEvent = (CounterChangedEvent) event.getPayload();
                if (counterChangedEvent.getCounter() == 1) {
                    commandBus.dispatch(asCommandMessage(
                            new ChangeCounterCommand(domainEvent.getAggregateIdentifier(),
                                                     counterChangedEvent.getCounter() + 1)), reportErrorCallback);
                    commandBus.dispatch(asCommandMessage(
                            new ChangeCounterCommand(domainEvent.getAggregateIdentifier(),
                                                     counterChangedEvent.getCounter() + 2)), reportErrorCallback);
                }
            }
        };
        eventBus.subscribe(new SimpleCluster("test", el));

        commandBus.dispatch(asCommandMessage(new ChangeCounterCommand(aggregateIdentifier, 1)), reportErrorCallback);

        DomainEventStream storedEvents = eventStore.readEvents(aggregateIdentifier);
        assertTrue(storedEvents.hasNext());
        while (storedEvents.hasNext()) {
            DomainEventMessage next = storedEvents.next();
            if (next.getPayload() instanceof CounterChangedEvent) {
                CounterChangedEvent event = (CounterChangedEvent) next.getPayload();
                assertEquals(event.getCounter(), next.getSequenceNumber());
            }
        }

        verify(eventStore, times(3)).appendEvents(anyEventList());
    }

    @Test
    public void testLoopBackKeepsProperEventOrder_OptimisticLocking_ProcessingFails() throws Throwable {
        initializeRepository(new OptimisticLockManager());
        EventListener el = event -> {
            DomainEventMessage domainEvent = (DomainEventMessage) event;
            if (event.getPayload() instanceof CounterChangedEvent) {
                CounterChangedEvent counterChangedEvent = (CounterChangedEvent) event.getPayload();
                if (counterChangedEvent.getCounter() == 1) {
                    commandBus.dispatch(asCommandMessage(
                            new ChangeCounterCommand(domainEvent.getAggregateIdentifier(),
                                                     counterChangedEvent.getCounter() + 1)), reportErrorCallback);
                    commandBus.dispatch(asCommandMessage(
                                                new ChangeCounterCommand(domainEvent.getAggregateIdentifier(),
                                                                         counterChangedEvent.getCounter() + 2)),
                                        reportErrorCallback);
                } else if (counterChangedEvent.getCounter() == 2) {
                    throw new RuntimeException("Mock exception");
                }
            }
        };
        eventBus.subscribe(new SimpleCluster("test", el));

        commandBus.dispatch(asCommandMessage(new ChangeCounterCommand(aggregateIdentifier, 1)), expectErrorCallback);

        DomainEventStream storedEvents = eventStore.readEvents(aggregateIdentifier);
        assertTrue(storedEvents.hasNext());
        while (storedEvents.hasNext()) {
            DomainEventMessage next = storedEvents.next();
            if (next.getPayload() instanceof CounterChangedEvent) {
                CounterChangedEvent event = (CounterChangedEvent) next.getPayload();
                assertEquals(event.getCounter(), next.getSequenceNumber());
            }
        }

        verify(eventStore, times(3)).appendEvents(anyEventList());
    }

    @Test
    public void testLoopBackKeepsProperEventOrder_PessimisticLocking_ProcessingFails() throws Throwable {
        initializeRepository(new PessimisticLockManager());
        EventListener el = event -> {
            DomainEventMessage domainEvent = (DomainEventMessage) event;
            if (event.getPayload() instanceof CounterChangedEvent) {
                CounterChangedEvent counterChangedEvent = (CounterChangedEvent) event.getPayload();
                if (counterChangedEvent.getCounter() == 1) {
                    commandBus.dispatch(asCommandMessage(
                                                new ChangeCounterCommand(domainEvent.getAggregateIdentifier(),
                                                                         counterChangedEvent.getCounter() + 1)),
                                        reportErrorCallback);
                    commandBus.dispatch(
                            asCommandMessage(new ChangeCounterCommand(domainEvent.getAggregateIdentifier(),
                                                                      counterChangedEvent.getCounter() + 2)),
                            reportErrorCallback);
                } else if (counterChangedEvent.getCounter() == 2) {
                    throw new RuntimeException("Mock exception");
                }
            }
        };
        eventBus.subscribe(new SimpleCluster("test", el));

        commandBus.dispatch(asCommandMessage(new ChangeCounterCommand(aggregateIdentifier, 1)), expectErrorCallback);

        DomainEventStream storedEvents = eventStore.readEvents(aggregateIdentifier);
        assertTrue(storedEvents.hasNext());
        while (storedEvents.hasNext()) {
            DomainEventMessage next = storedEvents.next();
            if (next.getPayload() instanceof CounterChangedEvent) {
                CounterChangedEvent event = (CounterChangedEvent) next.getPayload();
                assertEquals(event.getCounter(), next.getSequenceNumber());
            }
        }

        verify(eventStore, times(3)).appendEvents(anyEventList());
    }

    @SuppressWarnings("unchecked")
    private static List<DomainEventMessage<?>> anyEventList() {
        return anyList();
    }

    private static class CounterCommandHandler {

        private Repository<CountingAggregate> repository;

        private CounterCommandHandler(Repository<CountingAggregate> repository) {
            this.repository = repository;
        }

        @CommandHandler
        @SuppressWarnings("unchecked")
        public void changeCounter(ChangeCounterCommand command) {
            CountingAggregate aggregate = repository.load(command.getAggregateId());
            aggregate.setCounter(command.getNewValue());
        }
    }

    private static class ChangeCounterCommand {

        private String aggregateId;
        private int newValue;

        private ChangeCounterCommand(String aggregateId, int newValue) {
            this.aggregateId = aggregateId;
            this.newValue = newValue;
        }

        public String getAggregateId() {
            return aggregateId;
        }

        public int getNewValue() {
            return newValue;
        }
    }

    private static class AggregateCreatedEvent {

        private final String aggregateIdentifier;

        private AggregateCreatedEvent(String aggregateIdentifier) {
            this.aggregateIdentifier = aggregateIdentifier;
        }

        public String getAggregateIdentifier() {
            return aggregateIdentifier;
        }
    }

    private static class CountingAggregate extends AbstractAnnotatedAggregateRoot {

        private static final long serialVersionUID = -2927751585905120260L;

        private int counter = 0;

        @AggregateIdentifier
        private String identifier;

        private CountingAggregate(String identifier) {
            apply(new AggregateCreatedEvent(identifier));
        }

        CountingAggregate() {
        }

        public void setCounter(int newValue) {
            apply(new CounterChangedEvent(newValue));
        }

        @EventSourcingHandler
        private void handleCreatedEvent(AggregateCreatedEvent event) {
            this.identifier = event.getAggregateIdentifier();
        }

        @EventSourcingHandler
        private void handleCounterIncreased(CounterChangedEvent event) {
            this.counter = event.getCounter();
        }
    }

    private static class CounterChangedEvent {

        private final int counter;

        private CounterChangedEvent(int counter) {
            this.counter = counter;
        }

        public int getCounter() {
            return counter;
        }
    }

    private class InMemoryEventStore implements EventStore {

        protected Map<Object, List<DomainEventMessage>> store = new HashMap<>();

        @Override
        public void appendEvents(List<DomainEventMessage<?>> events) {
            for (EventMessage event : events) {
                DomainEventMessage next = (DomainEventMessage) event;
                if (!store.containsKey(next.getAggregateIdentifier())) {
                    store.put(next.getAggregateIdentifier(), new ArrayList<>());
                }
                List<DomainEventMessage> eventList = store.get(next.getAggregateIdentifier());
                eventList.add(next);
            }
        }

        @Override
        public DomainEventStream readEvents(String identifier, long firstSequenceNumber,
                                            long lastSequenceNumber) {
            if (!store.containsKey(identifier)) {
                throw new AggregateNotFoundException(identifier, "Aggregate not found");
            }
            final List<DomainEventMessage> events = store.get(identifier);
            List<DomainEventMessage> filteredEvents = new ArrayList<>();
            for (DomainEventMessage message : events) {
                if (message.getSequenceNumber() >= firstSequenceNumber
                        && message.getSequenceNumber() <= lastSequenceNumber) {
                    filteredEvents.add(message);
                }
            }
            return new SimpleDomainEventStream(filteredEvents);
        }
    }
}
