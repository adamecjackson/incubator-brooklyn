/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.entity.basic;

import static org.testng.Assert.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import brooklyn.entity.proxying.EntitySpec;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.event.basic.BasicSensorEvent;
import brooklyn.location.basic.SimulatedLocation;
import brooklyn.management.SubscriptionHandle;
import brooklyn.test.Asserts;
import brooklyn.test.entity.TestApplication;
import brooklyn.test.entity.TestEntity;

import com.google.common.collect.ImmutableList;

public class EntitySubscriptionTest {

    // TODO Duplication between this and PolicySubscriptionTest
    
    private SimulatedLocation loc;
    private TestApplication app;
    private TestEntity entity;
    private TestEntity observedEntity;
    private BasicGroup observedGroup;
    private TestEntity observedChildEntity;
    private TestEntity observedMemberEntity;
    private TestEntity otherEntity;
    private RecordingSensorEventListener listener;
    
    @BeforeMethod(alwaysRun=true)
    public void setUp() {
        app = TestApplication.Factory.newManagedInstanceForTests();
        loc = app.newSimulatedLocation();
        entity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        observedEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        observedChildEntity = observedEntity.createAndManageChild(EntitySpec.create(TestEntity.class));

        observedGroup = app.createAndManageChild(EntitySpec.create(BasicGroup.class));
        observedMemberEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        observedGroup.addMember(observedMemberEntity);
        
        otherEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        
        listener = new RecordingSensorEventListener();
        
        app.start(ImmutableList.of(loc));
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() {
        if (app != null) Entities.destroyAll(app.getManagementContext());
    }
    
    @Test
    public void testSubscriptionReceivesEvents() {
        entity.subscribe(observedEntity, TestEntity.SEQUENCE, listener);
        entity.subscribe(observedEntity, TestEntity.NAME, listener);
        entity.subscribe(observedEntity, TestEntity.MY_NOTIF, listener);
        
        otherEntity.setAttribute(TestEntity.SEQUENCE, 123);
        observedEntity.setAttribute(TestEntity.SEQUENCE, 123);
        observedEntity.setAttribute(TestEntity.NAME, "myname");
        observedEntity.emit(TestEntity.MY_NOTIF, 456);
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events, ImmutableList.of(
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, observedEntity, 123),
                        new BasicSensorEvent<String>(TestEntity.NAME, observedEntity, "myname"),
                        new BasicSensorEvent<Integer>(TestEntity.MY_NOTIF, observedEntity, 456)));
            }});
    }
    
    @Test
    public void testSubscriptionToAllReceivesEvents() {
        entity.subscribe(null, TestEntity.SEQUENCE, listener);
        
        observedEntity.setAttribute(TestEntity.SEQUENCE, 123);
        otherEntity.setAttribute(TestEntity.SEQUENCE, 456);
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events, ImmutableList.of(
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, observedEntity, 123),
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, otherEntity, 456)));
            }});
    }
    
    @Test
    public void testSubscribeToChildrenReceivesEvents() {
        entity.subscribeToChildren(observedEntity, TestEntity.SEQUENCE, listener);
        
        observedChildEntity.setAttribute(TestEntity.SEQUENCE, 123);
        observedEntity.setAttribute(TestEntity.SEQUENCE, 456);
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events, ImmutableList.of(
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, observedChildEntity, 123)));
            }});
    }
    
    @Test
    public void testSubscribeToChildrenReceivesEventsForDynamicallyAddedChildren() {
        entity.subscribeToChildren(observedEntity, TestEntity.SEQUENCE, listener);
        
        final TestEntity observedChildEntity2 = observedEntity.createAndManageChild(EntitySpec.create(TestEntity.class));
        observedChildEntity2.setAttribute(TestEntity.SEQUENCE, 123);
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events, ImmutableList.of(
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, observedChildEntity2, 123)));
            }});
    }
    
    @Test
    public void testSubscribeToMembersReceivesEvents() {
        entity.subscribeToMembers(observedGroup, TestEntity.SEQUENCE, listener);
        
        observedMemberEntity.setAttribute(TestEntity.SEQUENCE, 123);
        ((EntityLocal)observedGroup).setAttribute(TestEntity.SEQUENCE, 456);
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events, ImmutableList.of(
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, observedMemberEntity, 123)));
            }});
    }
    
    @Test
    public void testSubscribeToMembersReceivesEventsForDynamicallyAddedMembers() {
        entity.subscribeToMembers(observedGroup, TestEntity.SEQUENCE, listener);
        
        final TestEntity observedMemberEntity2 = app.createAndManageChild(EntitySpec.create(TestEntity.class));
        observedGroup.addMember(observedMemberEntity2);
        observedMemberEntity2.setAttribute(TestEntity.SEQUENCE, 123);
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events, ImmutableList.of(
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, observedMemberEntity2, 123)));
            }});
    }
    
    @Test(groups="Integration")
    public void testSubscribeToMembersIgnoresEventsForDynamicallyRemovedMembers() {
        entity.subscribeToMembers(observedGroup, TestEntity.SEQUENCE, listener);
        
        observedGroup.removeMember(observedMemberEntity);
        
        observedMemberEntity.setAttribute(TestEntity.SEQUENCE, 123);
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events, ImmutableList.of());
            }});
    }
    
    @Test
    public void testUnsubscribeRemovesAllSubscriptionsForThatEntity() {
        entity.subscribe(observedEntity, TestEntity.SEQUENCE, listener);
        entity.subscribe(observedEntity, TestEntity.NAME, listener);
        entity.subscribe(observedEntity, TestEntity.MY_NOTIF, listener);
        entity.subscribe(otherEntity, TestEntity.SEQUENCE, listener);
        entity.unsubscribe(observedEntity);
        
        observedEntity.setAttribute(TestEntity.SEQUENCE, 123);
        observedEntity.setAttribute(TestEntity.NAME, "myname");
        observedEntity.emit(TestEntity.MY_NOTIF, 123);
        otherEntity.setAttribute(TestEntity.SEQUENCE, 456);
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events, ImmutableList.of(
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, otherEntity, 456)));
            }});
    }
    
    @Test
    public void testUnsubscribeUsingHandleStopsEvents() {
        SubscriptionHandle handle1 = entity.subscribe(observedEntity, TestEntity.SEQUENCE, listener);
        SubscriptionHandle handle2 = entity.subscribe(observedEntity, TestEntity.NAME, listener);
        SubscriptionHandle handle3 = entity.subscribe(otherEntity, TestEntity.SEQUENCE, listener);
        
        entity.unsubscribe(observedEntity, handle2);
        
        observedEntity.setAttribute(TestEntity.SEQUENCE, 123);
        observedEntity.setAttribute(TestEntity.NAME, "myname");
        otherEntity.setAttribute(TestEntity.SEQUENCE, 456);
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events, ImmutableList.of(
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, observedEntity, 123),
                        new BasicSensorEvent<Integer>(TestEntity.SEQUENCE, otherEntity, 456)));
            }});
    }
    
    @Test
    public void testSubscriptionReceivesEventsInOrder() {
        final int NUM_EVENTS = 100;
        entity.subscribe(observedEntity, TestEntity.MY_NOTIF, listener);

        for (int i = 0; i < NUM_EVENTS; i++) {
            observedEntity.emit(TestEntity.MY_NOTIF, i);
        }
        
        Asserts.succeedsEventually(new Runnable() {
            @Override public void run() {
                assertEquals(listener.events.size(), NUM_EVENTS);
                for (int i = 0; i < NUM_EVENTS; i++) {
                    assertEquals(listener.events.get(i).getValue(), i);
                }
            }});
    }

    public static class RecordingSensorEventListener implements SensorEventListener<Object> {
        public final List<SensorEvent<?>> events = new CopyOnWriteArrayList<SensorEvent<?>>();
        
        @Override public void onEvent(SensorEvent<Object> event) {
            events.add(event);
        }
    }
}
