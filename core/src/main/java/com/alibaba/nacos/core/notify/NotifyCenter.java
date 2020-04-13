/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.core.notify;

import com.alibaba.nacos.common.utils.ShutdownUtils;
import com.alibaba.nacos.core.notify.listener.SmartSubscribe;
import com.alibaba.nacos.core.notify.listener.Subscribe;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacos.core.utils.DisruptorFactory;
import com.alibaba.nacos.core.utils.Loggers;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.dsl.Disruptor;

import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
@SuppressWarnings("all")
public class NotifyCenter {

    private static final Map<String, Publisher> PUBLISHER_MAP = new ConcurrentHashMap<>(16);
    private static final Set<SmartSubscribe> SMART_SUBSCRIBES = new ConcurrentHashSet<>();

    private static boolean stopDeferPublish = false;

    static {
        ShutdownUtils.addShutdownHook(new Thread(() -> {
            System.out.println("[NotifyCenter] Start destroying Publisher");
            try {
                PUBLISHER_MAP.forEach(new BiConsumer<String, Publisher>() {
                    @Override
                    public void accept(String s, Publisher publisher) {
                        publisher.shutdown();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println("[NotifyCenter] Destruction of the end");
        }));

    }

    public static void stopDeferPublish() {
        stopDeferPublish = true;
    }

    /**
     * Register a Subscriber. If the Publisher concerned by the
     * Subscriber does not exist, then PublihserMap will preempt
     * a placeholder Publisher first. not call {@link Publisher#start()}
     *
     * @param eventType Types of events that Subscriber cares about
     * @param consumer  subscriber
     * @param <T>       event type
     */
    public static <T> void registerSubscribe(final Subscribe consumer) {
        if (consumer instanceof SmartSubscribe) {
            SMART_SUBSCRIBES.add((SmartSubscribe) consumer);
            return;
        }
        final String topic = consumer.subscribeType().getCanonicalName();
        PUBLISHER_MAP.computeIfAbsent(topic, s -> new Publisher(consumer.subscribeType()));
        Publisher publisher = PUBLISHER_MAP.get(topic);
        publisher.addSubscribe(consumer);
    }

    /**
     * deregister subscriber
     *
     * @param consumer subscriber
     * @param <T>
     */
    public static <T> void deregisterSubscribe(final Subscribe consumer) {
        if (consumer instanceof SmartSubscribe) {
            SMART_SUBSCRIBES.remove((SmartSubscribe) consumer);
            return;
        }
        final String topic = consumer.subscribeType().getCanonicalName();
        if (PUBLISHER_MAP.containsKey(topic)) {
            Publisher publisher = PUBLISHER_MAP.get(topic);
            publisher.unSubscribe(consumer);
            return;
        }
        throw new NoSuchElementException("The subcriber has no event publisher");
    }

    /**
     * request publisher publish event
     * Publishers load lazily, calling publisher. Start () only when the event is actually published
     *
     * @param event
     */
    public static void publishEvent(final Event event) {
        publishEvent(event.eventType(), event);
    }

    /**
     * request publisher publish event
     * Publishers load lazily, calling publisher. Start () only when the event is actually published
     *
     * @param eventType
     * @param event
     */
    private static void publishEvent(final Class<? extends Event> eventType,
                                    final Event event) {
        final String topic = eventType.getCanonicalName();
        if (PUBLISHER_MAP.containsKey(topic)) {
            Publisher publisher = PUBLISHER_MAP.get(topic);
            if (!publisher.isInitialized()) {
                publisher.start();
            }
            publisher.publish(event);
            return;
        }
        throw new NoSuchElementException("There are no event publishers for this event, please register");
    }

    /**
     * register publisher
     *
     * @param supplier
     * @param eventType
     * @return
     */
    public static Publisher registerPublisher(final Supplier<? extends Event> supplier,
                                              final Class<? extends Event> eventType) {
        final String topic = eventType.getCanonicalName();
        PUBLISHER_MAP.computeIfAbsent(topic, s -> {
            Publisher publisher = new Publisher(eventType);
            return publisher;
        });
        Publisher publisher = PUBLISHER_MAP.get(topic);
        publisher.setSupplier(supplier);
        return publisher;
    }

    /**
     * deregister publisher
     *
     * @param eventType
     * @return
     */
    public static void deregisterPublisher(final Class<? extends Event> eventType) {
        final String topic = eventType.getCanonicalName();
        Publisher publisher = PUBLISHER_MAP.remove(topic);
        publisher.shutdown();
    }

    private static class Publisher {

        private final Class<? extends Event> eventType;
        private final CopyOnWriteArraySet<Subscribe> subscribes = new CopyOnWriteArraySet<>();
        private volatile boolean initialized = false;
        private Disruptor<EventHandle> disruptor;
        private Supplier<? extends Event> supplier;
        private volatile boolean canOpen = false;
        private volatile boolean shutdown = false;

        public Publisher(final Class<? extends Event> eventType) {
            this.eventType = eventType;
        }

        void setSupplier(Supplier<? extends Event> supplier) {
            this.supplier = supplier;
        }

        synchronized void start() {
            if (!initialized) {
                this.disruptor = DisruptorFactory.build((EventFactory) () -> {
                    return new EventHandle(supplier.get());
                }, eventType);
                openEventHandler();
                this.disruptor.start();
                initialized = true;
            }
        }

        void openEventHandler() {
            this.disruptor.handleEventsWith(new EventHandler<EventHandle>() {
                @Override
                public void onEvent(EventHandle handle, long sequence, boolean endOfBatch) throws Exception {

                    // To ensure that messages are not lost, enable EventHandler when
                    // waiting for the first Subscriber to register

                    for (; ; ) {
                        if (shutdown || canOpen || stopDeferPublish) {
                            break;
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignore) {
                            Thread.interrupted();
                        }
                    }

                    Set<Subscribe> tmp = new HashSet<>();
                    tmp.addAll(SMART_SUBSCRIBES);
                    tmp.addAll(subscribes);

                    for (Subscribe subscribe : tmp) {
                        final Event event = handle.getEvent();
                        if (subscribe instanceof SmartSubscribe) {
                            if (!((SmartSubscribe) subscribe).canNotify(event)) {
                                continue;
                            }
                        }
                        final Runnable job = () -> subscribe.onEvent(handle.getEvent());
                        final Executor executor = subscribe.executor();
                        if (Objects.nonNull(executor)) {
                            executor.execute(job);
                        } else {
                            try {
                                job.run();
                            } catch (Exception e) {
                                Loggers.CORE.error("Event callback exception : {}", e);
                            }
                        }
                    }
                }
            });
        }

        void addSubscribe(Subscribe subscribe) {
            subscribes.add(subscribe);
            canOpen = true;
        }

        void unSubscribe(Subscribe subscribe) {
            subscribes.remove(subscribe);
        }

        void publish(Event event) {
            checkIsStart();
            this.disruptor.publishEvent(new EventTranslator<EventHandle>() {
                @Override
                public void translateTo(EventHandle eventHandle, long sequence) {
                    eventHandle.setEvent(event);
                }
            });
        }

        void checkIsStart() {
            if (!initialized) {
                throw new IllegalStateException("Publisher does not start");
            }
        }

        void shutdown() {
            if (disruptor != null) {
                shutdown = true;
                disruptor.shutdown();
            }
        }

        public boolean isInitialized() {
            return initialized;
        }
    }

}
