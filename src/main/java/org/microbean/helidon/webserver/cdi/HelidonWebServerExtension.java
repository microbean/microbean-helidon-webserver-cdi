/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2018 microBean.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.helidon.webserver.cdi;

import java.lang.annotation.Annotation;

import java.lang.reflect.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import java.util.stream.Collectors;

import javax.annotation.Priority;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BeforeDestroyed;
import javax.enterprise.context.Initialized;

import javax.enterprise.context.control.RequestContextController;

import javax.enterprise.context.spi.CreationalContext;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;

import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.InterceptionFactory;
import javax.enterprise.inject.spi.ProcessInjectionTarget;

import javax.enterprise.util.AnnotationLiteral;

import javax.inject.Qualifier;
import javax.inject.Singleton;

import javax.interceptor.Interceptor;

import io.helidon.config.Config;

import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerConfiguration;
import io.helidon.webserver.Service;
import io.helidon.webserver.WebServer;

import io.helidon.webserver.spi.BareRequest;
import io.helidon.webserver.spi.BareResponse;

/**
 * A <a
 * href="http://docs.jboss.org/cdi/spec/2.0/cdi-spec.html">CDI</a> <a
 * href="http://docs.jboss.org/cdi/spec/2.0/cdi-spec.html#spi">portable
 * extension</a> that integrates {@link Config}, {@link
 * io.helidon.config.Config.Builder}, {@link WebServer}, {@link
 * io.helidon.webserver.WebServer.Builder}, {@link Routing}, {@link
 * io.helidon.webserver.Routing.Builder}, {@link ServerConfiguration},
 * and {@link io.helidon.webserver.ServerConfiguration.Builder} instances
 * into a CDI application.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 */
public class HelidonWebServerExtension implements javax.enterprise.inject.spi.Extension {

  private final Set<Set<Annotation>> serviceQualifiers;

  private volatile CountDownLatch webServersLatch;

  // @GuardedBy("self")
  private final Collection<Throwable> errors;

  /**
   * Creates a new {@link HelidonWebServerExtension}.
   */
  public HelidonWebServerExtension() {
    super();
    this.errors = new ArrayList<>(3);
    this.serviceQualifiers = new HashSet<>();
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutDown));
  }

  /**
   * A brute force method that shuts down all {@link WebServer}
   * instances that have been {@linkplain WebServer#start() started}
   * by this {@link HelidonWebServerExtension}.  Most users should not
   * call this method.
   */
  public void shutDown() {
    final CountDownLatch latch = this.webServersLatch;
    if (latch != null) {
      while (latch.getCount() > 0L) {
        latch.countDown();
      }
    }
  }

  /**
   * Modifies each {@link InjectionTarget} representing a {@link
   * Service}-typed CDI bean in the application such that its {@link
   * Service#update(Routing.Rules.class)} method is called during
   * post-construction.
   *
   * <p>In addition, this method saves the {@link Set} of {@linkplain
   * Qualifier qualifier annotations} found on the {@linkplain
   * ProcessInjectionTarget#getAnnotatedType()
   * <code>AnnotatedType</code> associated with the supplied
   * <code>ProcessInjectionTarget</code> event} for later use.</p>
   *
   * @param event the {@link ProcessInjectionTarget} event being
   * observed; must not be {@code null}
   *
   * @param beanManager the {@link BeanManager} for the current
   * application; must not be {@code null}
   *
   * @see #serviceQualifiers
   *
   * @see InjectionTarget#postConstruct(Object)
   *
   * @see Service#update(Routing.Rules.class)
   *
   * @exception NullPointerException if either {@code event} or {@code
   * beanManager} is {@code null}
   */
  private <T extends Service> void processServiceInjectionTarget(@Observes final ProcessInjectionTarget<T> event,
                                                                 final BeanManager beanManager) {
    Objects.requireNonNull(event);
    Objects.requireNonNull(beanManager);
    
    final Set<Annotation> qualifiers = getQualifiers(event.getAnnotatedType());
    assert qualifiers != null;
    assert qualifiers.contains(Any.Literal.INSTANCE);
    this.serviceQualifiers.add(qualifiers);

    final Annotation[] qualifiersArray = qualifiers.toArray(new Annotation[qualifiers.size()]);
    assert qualifiersArray != null;

    event.setInjectionTarget(new DelegatingInjectionTarget<T>(event.getInjectionTarget()) {
        @Override
        public void postConstruct(final T service) {
          super.postConstruct(service);
          // Call Service#update(Routing.Rules) as a sort of
          // postConstruct method; that's really the only purpose of a
          // Service.
          service.update(getReference(beanManager, Routing.Rules.class, qualifiersArray));
        }
      });
  }

  private void addBeans(@Observes final AfterBeanDiscovery event, final BeanManager beanManager) {
    Objects.requireNonNull(event);
    Objects.requireNonNull(beanManager);

    if (!this.serviceQualifiers.isEmpty()) {
      for (final Set<Annotation> qualifiers : this.serviceQualifiers) {
        assert qualifiers != null;
        assert !qualifiers.isEmpty();
        final Annotation[] qualifiersArray = qualifiers.toArray(new Annotation[qualifiers.size()]);

        // Config.Builder
        if (noBean(beanManager, Config.Builder.class, qualifiersArray)) {
          event.<Config.Builder>addBean()
            .addType(Config.Builder.class)
            .scope(ApplicationScoped.class)
            .qualifiers(qualifiers)
            .createWith(cc -> createConfigBuilder(beanManager, qualifiersArray));
        }

        // Config
        if (noBean(beanManager, Config.class, qualifiersArray)) {
          event.<Config>addBean()
            .addType(Config.class)
            .scope(ApplicationScoped.class)
            .qualifiers(qualifiers)
            .createWith(cc -> createConfig(beanManager, qualifiersArray));
        }
        
        // Per-WebServer Executor
        if (noBean(beanManager, Executor.class, qualifiersArray)) {
          event.<Executor>addBean()
            .addTransitiveTypeClosure(ExecutorService.class)
            .scope(ApplicationScoped.class)
            .qualifiers(qualifiers)
            .createWith(cc -> Executors.newCachedThreadPool());
        }

        // Routing.Builder
        if (noBean(beanManager, Routing.Rules.class, qualifiersArray)) {
          event.<Routing.Builder>addBean()
            .addTransitiveTypeClosure(Routing.Builder.class)
            .addType(Routing.Rules.class) // have to do this because Service.update(Rules) uses raw type
            .scope(ApplicationScoped.class)
            .qualifiers(qualifiers)
            .createWith(cc -> createRoutingBuilder(beanManager, qualifiersArray));
        }

        // Routing
        if (noBean(beanManager, Routing.class, qualifiersArray)) {
          event.<Routing>addBean()
            .addTransitiveTypeClosure(Routing.class)
            .scope(ApplicationScoped.class)
            .qualifiers(qualifiers)
            .createWith(cc -> createRouting(cc, beanManager, qualifiersArray));
        }

        // ServerConfiguration.Builder
        if (noBean(beanManager, ServerConfiguration.Builder.class, qualifiersArray)) {
          event.<ServerConfiguration.Builder>addBean()
            .addTransitiveTypeClosure(ServerConfiguration.Builder.class)
            .scope(ApplicationScoped.class)
            .qualifiers(qualifiers)
            .createWith(cc -> createServerConfigurationBuilder(beanManager, qualifiersArray));
        }

        // ServerConfiguration
        if (noBean(beanManager, ServerConfiguration.class, qualifiersArray)) {
          event.<ServerConfiguration>addBean()
            .addTransitiveTypeClosure(ServerConfiguration.class)
            .scope(ApplicationScoped.class)
            .qualifiers(qualifiers)
            .createWith(cc -> createServerConfiguration(beanManager, qualifiersArray));
        }

        // WebServer.Builder
        if (noBean(beanManager, WebServer.Builder.class, qualifiersArray)) {
          event.<WebServer.Builder>addBean()
            .addTransitiveTypeClosure(WebServer.Builder.class)
            .scope(Singleton.class) // can't be ApplicationScoped because it's final :-(
            .qualifiers(qualifiers)
            .createWith(cc -> createWebServerBuilder(beanManager, qualifiersArray));
        }

        // WebServer
        if (noBean(beanManager, WebServer.class, qualifiersArray)) {
          event.<WebServer>addBean()
            .addTransitiveTypeClosure(WebServer.class)
            .scope(ApplicationScoped.class)
            .qualifiers(qualifiers)
            .createWith(cc -> createWebServer(beanManager, qualifiersArray))
            .destroyWith(HelidonWebServerExtension::destroyWebServer);
        }

      }

    }
  }

  private void onStartup(@Observes
                         @Initialized(ApplicationScoped.class)
                         @Priority(Interceptor.Priority.PLATFORM_BEFORE)
                         final Object event,
                         final BeanManager beanManager) {
    Objects.requireNonNull(event);
    Objects.requireNonNull(beanManager);

    if (!this.serviceQualifiers.isEmpty()) {

      this.webServersLatch = new CountDownLatch(this.serviceQualifiers.size());

      for (final Set<Annotation> qualifiers : this.serviceQualifiers) {
        assert qualifiers != null;
        assert !qualifiers.isEmpty();
        final Annotation[] qualifiersArray = qualifiers.toArray(new Annotation[qualifiers.size()]);

        final Set<Bean<?>> serviceBeans = beanManager.getBeans(Service.class, qualifiersArray);
        assert serviceBeans != null;
        if (!serviceBeans.isEmpty()) {

          for (final Bean<?> bean : serviceBeans) {
            assert bean != null;

            @SuppressWarnings("unchecked")
            final Bean<Service> serviceBean = (Bean<Service>) bean;

            // Eagerly instantiate all Service instances, whether
            // CDI's typesafe resolution mechanism would fail or not.
            // This instantiation strategy (e.g. a direct call to
            // Context#get(Bean, CreationalContext)) is OK here
            // because we're not actually going to make use of the
            // reference returned.
            beanManager.getContext(serviceBean.getScope())
              .get(serviceBean,
                   beanManager.createCreationalContext(serviceBean));

            // All Routings should now be set up
          }

          final WebServer webServer = getReference(beanManager, WebServer.class, qualifiersArray);
          assert webServer != null;

          webServer.whenShutdown()
            .whenComplete((ws, throwable) -> {
                try {
                  if (throwable != null) {
                    synchronized (errors) {
                      errors.add(throwable);
                    }
                  }
                } finally {
                  // Note the nesting; whether there's an error or
                  // not!
                  webServersLatch.countDown();
                }
              });
          
          webServer.start()
            .whenComplete((ws, throwable) -> {
                if (throwable != null) {
                  if (throwable instanceof CancellationException) {
                    assert !ws.isRunning();
                    // The behavior of CompletableFuture is a little
                    // tricky.  If someone shuts the webserver down
                    // before it has finished starting, the task that
                    // is responsible for starting it will be
                    // cancelled.  This will cause an exceptional
                    // completion of the start task.  That is
                    // delivered here.  All it means is that the
                    // webserver's startup machinery has been
                    // cancelled explicitly.  This is not worth
                    // re-throwing.
                  } else {
                    try {
                      synchronized (errors) {
                        errors.add(throwable);
                      }
                    } finally {
                      // Note the nesting; only when there's an error!
                      webServersLatch.countDown();
                    }
                  }
                }
              });

        }
      }

    }
  }

  private void onShutdown(@Observes
                          @BeforeDestroyed(ApplicationScoped.class)
                          @Priority(Interceptor.Priority.PLATFORM_BEFORE - 1)
                          final Object event)
    throws Throwable {
    final CountDownLatch latch = this.webServersLatch;
    if (latch != null && latch.getCount() > 0L) {
      this.webServersLatch.await();
    }
    Throwable throwMe = null;
    synchronized (this.errors) {
      if (!this.errors.isEmpty()) {
        final Iterator<? extends Throwable> iterator = this.errors.iterator();
        assert iterator != null;
        while (iterator.hasNext()) {
          final Throwable t = iterator.next();
          assert t != null;
          iterator.remove();
          if (throwMe == null) {
            throwMe = t;
          } else {
            throwMe.addSuppressed(t);
          }
        }
      }
    }
    if (throwMe != null) {
      throw throwMe;
    }
  }


  /*
   * Handy creation methods used in createWith() calls above.
   */

  
  private static Config.Builder createConfigBuilder(final BeanManager beanManager,
                                                    final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final Config.Builder returnValue = Config.builder();
    assert returnValue != null;
    // Permit arbitrary customization.
    beanManager.getEvent().select(Config.Builder.class, qualifiers).fire(returnValue);
    return returnValue;
  }

  private static Config createConfig(final BeanManager beanManager,
                                     final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final Config.Builder builder = getReference(beanManager, Config.Builder.class, qualifiers);
    assert builder != null;
    final Config returnValue = builder.build();
    assert returnValue != null;
    return returnValue;
  }

  private static Routing.Builder createRoutingBuilder(final BeanManager beanManager,
                                                      final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final Routing.Builder returnValue = Routing.builder();
    assert returnValue != null;
    // Permit arbitrary customization.
    beanManager.getEvent().select(Routing.Builder.class, qualifiers).fire(returnValue);
    return returnValue;
  }

  private static Routing createRouting(final CreationalContext<?> cc,
                                       final BeanManager beanManager,
                                       final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final Routing.Builder builder = getReference(beanManager, Routing.Builder.class, qualifiers);
    assert builder != null;
    final Routing routing = builder.build();
    assert routing != null;
    final RequestContextController requestContextActivator = getReference(beanManager, RequestContextController.class);
    assert requestContextActivator != null;
    final Executor executor = getReference(beanManager, Executor.class, qualifiers);    
    final Routing returnValue = new ExecutorBackedRouting(new RequestContextActivatingRouting(routing, requestContextActivator), executor);
    return returnValue;
  }

  private static ServerConfiguration.Builder createServerConfigurationBuilder(final BeanManager beanManager,
                                                                              final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final Config config = getReference(beanManager, Config.class);
    assert config != null;

    final ServerConfiguration.Builder returnValue = ServerConfiguration.builder(config);
    assert returnValue != null;
    // Permit arbitrary customization.
    beanManager.getEvent().select(ServerConfiguration.Builder.class, qualifiers).fire(returnValue);
    return returnValue;
  }

  private static ServerConfiguration createServerConfiguration(final BeanManager beanManager,
                                                               final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final ServerConfiguration.Builder builder = getReference(beanManager, ServerConfiguration.Builder.class, qualifiers);
    assert builder != null;
    final ServerConfiguration returnValue = builder.build();
    assert returnValue != null;
    return returnValue;
  }

  private static WebServer.Builder createWebServerBuilder(final BeanManager beanManager,
                                                          final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final Routing routing = getReference(beanManager, Routing.class, qualifiers);
    assert routing != null;

    final WebServer.Builder returnValue = WebServer.builder(routing);
    assert returnValue != null;
    // Permit arbitrary customization.
    beanManager.getEvent().select(WebServer.Builder.class, qualifiers).fire(returnValue);
    return returnValue;
  }

  private static WebServer createWebServer(final BeanManager beanManager,
                                           final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final WebServer.Builder builder = getReference(beanManager, WebServer.Builder.class, qualifiers);
    assert builder != null;

    final WebServer returnValue = builder.build();
    assert returnValue != null;
    return returnValue;
  }

  private static void destroyWebServer(final WebServer webServer,
                                       final CreationalContext<WebServer> cc)
  {
    Objects.requireNonNull(webServer).shutdown();
  }


  /*
   * Utility methods.
   */


  private static Set<Annotation> getQualifiers(final Annotated type) {
    return getQualifiers(Objects.requireNonNull(type).getAnnotations());
  }

  private static Set<Annotation> getQualifiers(final Set<? extends Annotation> annotations) {
    final Set<Annotation> qualifiers = Objects.requireNonNull(annotations).stream()
      .filter(a -> a.annotationType().isAnnotationPresent(Qualifier.class))
      .collect(Collectors.toCollection(HashSet::new));
    assert qualifiers != null;
    qualifiers.add(Default.Literal.INSTANCE);
    qualifiers.add(Any.Literal.INSTANCE);
    final Set<Annotation> returnValue = Collections.unmodifiableSet(qualifiers);
    return returnValue;
  }

  private static boolean noBean(final BeanManager beanManager, final Type type, final Annotation... qualifiers) {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(type);
    final Collection<?> beans;
    if (qualifiers == null || qualifiers.length <= 0) {
      beans = beanManager.getBeans(type);
    } else {
      beans = beanManager.getBeans(type, qualifiers);
    }
    final boolean returnValue = beans == null || beans.isEmpty();
    return returnValue;
  }

  private static <T> T getReference(final BeanManager beanManager, final Class<? extends T> cls, final Annotation... qualifiers) {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(cls);

    final Bean<?> bean;    
    if (qualifiers == null || qualifiers.length <= 0) {
      bean = beanManager.resolve(beanManager.getBeans(cls));
    } else {
      bean = beanManager.resolve(beanManager.getBeans(cls, qualifiers));
    }
    final T returnValue;
    if (bean == null) {
      returnValue = null;
    } else {
      returnValue = cls.cast(beanManager.getReference(bean, cls, beanManager.createCreationalContext(bean)));
    }
    return returnValue;
  }


  /*
   * Inner and nested classes.
   */


  private static class DelegatingInjectionTarget<T> implements InjectionTarget<T> {

    private final InjectionTarget<T> delegate;

    private DelegatingInjectionTarget(final InjectionTarget<T> delegate) {
      super();
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public T produce(final CreationalContext<T> cc) {
      return this.delegate.produce(cc);
    }

    @Override
    public void dispose(final T service) {
      this.delegate.dispose(service);
    }

    @Override
    public Set<InjectionPoint> getInjectionPoints() {
      return this.delegate.getInjectionPoints();
    }

    @Override
    public void inject(final T service, final CreationalContext<T> cc) {
      this.delegate.inject(service, cc);
    }

    @Override
    public void preDestroy(final T service) {
      this.delegate.preDestroy(service);
    }

    @Override
    public void postConstruct(final T service) {
      this.delegate.postConstruct(service);
    }

  }

  private static abstract class DelegatingRouting implements Routing {

    private final Routing delegate;

    private DelegatingRouting(final Routing delegate) {
      super();
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public WebServer createServer() {
      return this.delegate.createServer();
    }

    @Override
    public WebServer createServer(final ServerConfiguration serverConfiguration) {
      return this.delegate.createServer(serverConfiguration);
    }

    @Override
    public void route(final BareRequest request, final BareResponse response) {
      this.delegate.route(request, response);
    }

  }

  private static final class ExecutorBackedRouting extends DelegatingRouting {

    private final Executor executor;
    
    private ExecutorBackedRouting(final Routing delegate) {
      this(delegate, null);
    }

    private ExecutorBackedRouting(final Routing delegate, final Executor executor) {
      super(delegate);
      if (executor == null) {
        this.executor = runnable -> runnable.run();
      } else {
        this.executor = executor;
      }
    }

    @Override
    public void route(final BareRequest request, final BareResponse response) {
      this.executor.execute(() -> {
          super.route(request, response);
        });
    }
    
  }

  private static final class RequestContextActivatingRouting extends DelegatingRouting {

    private final RequestContextController requestContextController;

    private RequestContextActivatingRouting(final Routing delegate, final RequestContextController requestContextController) {
      super(delegate);
      this.requestContextController = Objects.requireNonNull(requestContextController);
    }

    @Override
    public void route(final BareRequest request, final BareResponse response) {
      try {
        this.requestContextController.activate();
        super.route(request, response);
      } finally {
        this.requestContextController.deactivate();
      }
    }

  }

}
