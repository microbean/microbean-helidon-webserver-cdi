/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2018–2019 microBean.
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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import java.util.stream.Collectors;

import javax.annotation.Priority;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BeforeDestroyed;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.Initialized;

import javax.enterprise.context.control.RequestContextController;

import javax.enterprise.context.spi.CreationalContext;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;

import javax.enterprise.inject.literal.InjectLiteral;

import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.AnnotatedParameter;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanAttributes;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessInjectionPoint;

import javax.enterprise.inject.spi.configurator.AnnotatedTypeConfigurator;

import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.inject.Singleton;

import javax.interceptor.Interceptor;

import io.helidon.common.http.ContextualRegistry;

import io.helidon.config.Config;

import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerConfiguration;
import io.helidon.webserver.Service;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.BareRequest;
import io.helidon.webserver.BareResponse;

import io.opentracing.Tracer;

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
public class HelidonWebServerExtension implements Extension {

  private final Set<Set<Annotation>> serviceQualifiers;

  private volatile CountDownLatch webServersLatch;

  // @GuardedBy("self")
  private final Collection<Throwable> errors;

  // @GuardedBy("self")
  private final Map<Class<?>, Integer> priorities;

  /**
   * Creates a new {@link HelidonWebServerExtension}.
   */
  public HelidonWebServerExtension() {
    super();
    this.errors = new ArrayList<>(3);
    this.serviceQualifiers = new HashSet<>();
    this.priorities = new HashMap<>();
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

  private <T extends Service> void processAnnotatedType(@Observes final ProcessAnnotatedType<T> event) {
    if (event != null) {
      final AnnotatedType<T> annotatedType = event.getAnnotatedType();
      assert annotatedType != null;
      final Class<?> javaClass = annotatedType.getJavaClass();
      assert javaClass != null;
      final Priority priority = annotatedType.getAnnotation(Priority.class);
      if (priority == null) {
        synchronized (this.priorities) {
          this.priorities.put(javaClass, Integer.valueOf(0));
        }
      } else {
        synchronized (this.priorities) {
          this.priorities.put(javaClass, Integer.valueOf(priority.value()));
        }
      }
      final AnnotatedTypeConfigurator<T> configurator = event.configureAnnotatedType();
      assert configurator != null;
      configurator.filterMethods(m -> {
          // Find the public void update(Routing.Rules) method.
          boolean returnValue = false;
          if (m != null && !m.isAnnotationPresent(Inject.class)) {
            final Method method = m.getJavaMember();
            if (method != null &&
                "update".equals(method.getName()) &&
                void.class.equals(method.getReturnType())) {
              final int modifiers = method.getModifiers();
              if (Modifier.isPublic(modifiers) &&
                  !Modifier.isStatic(modifiers)) {
                final List<? extends AnnotatedParameter<?>> parameters = m.getParameters();
                if (parameters != null && parameters.size() == 1) {
                  final AnnotatedParameter<?> soleParameter = parameters.get(0);
                  if (soleParameter != null) {
                    final Parameter javaParameter = soleParameter.getJavaParameter();
                    if (javaParameter != null &&
                        Routing.Rules.class.equals(javaParameter.getType())) {
                      returnValue = true;
                    }
                  }
                }
              }
            }
          }
          return returnValue;
        })
        .findFirst()
        .get()
        .add(InjectLiteral.INSTANCE);        
    }
  }

  @SuppressWarnings("rawtypes") // yes, on purpose
  private <S extends Service> void processUpdateInjectionPoint(@Observes final ProcessInjectionPoint<S, Routing.Rules> event) {
    if (event != null) {
      final InjectionPoint injectionPoint = event.getInjectionPoint();
      if (injectionPoint != null) {
        final BeanAttributes<?> beanAttributes = injectionPoint.getBean();
        if (beanAttributes != null) {
          final Set<Annotation> beanQualifiers = beanAttributes.getQualifiers();
          this.serviceQualifiers.add(beanQualifiers);
          event.configureInjectionPoint()
            .qualifiers(beanQualifiers);
        }
      }
    }
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
            .scope(Singleton.class) // can't be ApplicationScoped because it's final :-(
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

        // Tracer
        if (noBean(beanManager, Tracer.class, qualifiersArray)) {
          event.<Tracer>addBean()
            .addTransitiveTypeClosure(Tracer.class)
            .scope(Dependent.class) // I guess
            .qualifiers(qualifiers)
            .createWith(cc -> createTracer(beanManager, qualifiersArray));
        }

        // ContextualRegistry
        if (noBean(beanManager, ContextualRegistry.class, qualifiersArray)) {
          event.<ContextualRegistry>addBean()
            .addTransitiveTypeClosure(ContextualRegistry.class)
            .scope(Dependent.class) // I guess
            .qualifiers(qualifiers)
            .createWith(cc -> createContextualRegistry(beanManager, qualifiersArray));
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

    try {

      if (!this.serviceQualifiers.isEmpty()) {

        final int serviceQualifiersSize = this.serviceQualifiers.size();
        this.webServersLatch = new CountDownLatch(serviceQualifiersSize);

        for (final Set<Annotation> qualifiers : this.serviceQualifiers) {
          assert qualifiers != null;
          assert !qualifiers.isEmpty();

          final Annotation[] qualifiersArray = qualifiers.toArray(new Annotation[serviceQualifiersSize]);

          final Set<Bean<?>> serviceBeans = new TreeSet<>(new BeanPriorityComparator());
          serviceBeans.addAll(beanManager.getBeans(Service.class, qualifiersArray));
        
          if (!serviceBeans.isEmpty()) {

            for (final Bean<?> bean : serviceBeans) {
              assert bean != null;
              @SuppressWarnings("unchecked")
                final Bean<Service> serviceBean = (Bean<Service>)bean;

              // Eagerly instantiate all Service instances, whether
              // CDI's typesafe resolution mechanism would fail or
              // not.  This instantiation strategy (e.g. a direct call
              // to Context#get(Bean, CreationalContext)) is OK here
              // because we're not actually going to make use of the
              // reference returned.  We are using it only so that the
              // update(Routing.Rules) methods are called before the
              // WebServer starts.
              beanManager.getContext(serviceBean.getScope())
                .get(serviceBean,
                     beanManager.createCreationalContext(serviceBean));
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
    } finally {
      synchronized (this.priorities) {
        this.priorities.clear();
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

  
  private static final Config.Builder createConfigBuilder(final BeanManager beanManager,
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

  private static final Config createConfig(final BeanManager beanManager,
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

  private static final Routing.Builder createRoutingBuilder(final BeanManager beanManager,
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

  private static final Routing createRouting(final CreationalContext<?> cc,
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

  private static final ServerConfiguration.Builder createServerConfigurationBuilder(final BeanManager beanManager,
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

  private static final ServerConfiguration createServerConfiguration(final BeanManager beanManager,
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

  private static final Tracer createTracer(final BeanManager beanManager,
                                     final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final ServerConfiguration serverConfiguration = getReference(beanManager, ServerConfiguration.class, qualifiers);
    assert serverConfiguration != null;

    final Tracer returnValue = serverConfiguration.tracer();
    assert returnValue != null;
    return returnValue;
  }

  private static final ContextualRegistry createContextualRegistry(final BeanManager beanManager,
                                                                   final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final WebServer webServer = getReference(beanManager, WebServer.class, qualifiers);
    assert webServer != null;

    final ContextualRegistry returnValue = webServer.context();
    assert returnValue != null;
    return returnValue;
  }

  private static final WebServer.Builder createWebServerBuilder(final BeanManager beanManager,
                                                                final Annotation... qualifiers)
  {
    Objects.requireNonNull(beanManager);
    Objects.requireNonNull(qualifiers);

    final Routing routing = getReference(beanManager, Routing.class, qualifiers);
    assert routing != null;

    final ServerConfiguration serverConfiguration = getReference(beanManager, ServerConfiguration.class, qualifiers);
    assert serverConfiguration != null;

    final WebServer.Builder returnValue = WebServer.builder(routing).config(serverConfiguration);
    assert returnValue != null;
    // Permit arbitrary customization.
    beanManager.getEvent().select(WebServer.Builder.class, qualifiers).fire(returnValue);
    return returnValue;
  }

  private static final WebServer createWebServer(final BeanManager beanManager,
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

  private static final void destroyWebServer(final WebServer webServer,
                                             final CreationalContext<WebServer> cc)
  {
    Objects.requireNonNull(webServer).shutdown();
  }


  /*
   * Utility methods.
   */


  private static final Set<Annotation> getQualifiers(final Annotated type) {
    return getQualifiers(Objects.requireNonNull(type).getAnnotations());
  }

  private static final Set<Annotation> getQualifiers(final Set<? extends Annotation> annotations) {
    final Set<Annotation> qualifiers = Objects.requireNonNull(annotations).stream()
      .filter(a -> a.annotationType().isAnnotationPresent(Qualifier.class))
      .collect(Collectors.toCollection(HashSet::new));
    assert qualifiers != null;
    qualifiers.add(Default.Literal.INSTANCE);
    qualifiers.add(Any.Literal.INSTANCE);
    final Set<Annotation> returnValue = Collections.unmodifiableSet(qualifiers);
    return returnValue;
  }

  private static final boolean noBean(final BeanManager beanManager, final Type type, final Annotation... qualifiers) {
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

  private static final <T> T getReference(final BeanManager beanManager, final Type cls, final Annotation... qualifiers) {
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
      @SuppressWarnings("unchecked")
      final T temp = (T)beanManager.getReference(bean, cls, beanManager.createCreationalContext(bean));
      returnValue = temp;
    }
    return returnValue;
  }


  /*
   * Inner and nested classes.
   */


  private static abstract class DelegatingRouting implements Routing {

    private final Routing delegate;

    private DelegatingRouting(final Routing delegate) {
      super();
      this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public final WebServer createServer() {
      return this.delegate.createServer();
    }

    @Override
    public final WebServer createServer(final ServerConfiguration serverConfiguration) {
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
    public final void route(final BareRequest request, final BareResponse response) {
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
    public final void route(final BareRequest request, final BareResponse response) {
      try {
        this.requestContextController.activate();
        super.route(request, response);
      } finally {
        this.requestContextController.deactivate();
      }
    }

  }

  private final class BeanPriorityComparator implements Comparator<Bean<?>> {

    @Override
    public final int compare(final Bean<?> bean1, final Bean<?> bean2) {
      final int returnValue;
      if (bean1 == null) {
        if (bean2 == null) {
          returnValue = 0;
        } else {
          returnValue = -1; // nulls sort "right"/to end of list
        }
      } else if (bean2 == null) {
        returnValue = 1;
      } else {
        final int bean1Priority = getPriority(bean1);
        final int bean2Priority = getPriority(bean2);
        if (bean1Priority == bean2Priority) {
          if (bean1.equals(bean2)) {
            returnValue = 0;
          } else {
            returnValue = bean1.toString().compareTo(bean2.toString());
          }
        } else if (bean1Priority < bean2Priority) {
          returnValue = -1;
        } else {
          returnValue = 1;
        }
      }
      return returnValue;
    }

    private final int getPriority(final BeanAttributes<?> bean) {
      int returnValue = 0;
      if (bean != null) {
        final Set<Type> types = bean.getTypes();
        assert types != null;
        assert !types.isEmpty();
        for (final Type type : types) {
          if (type instanceof Class) {
            final Class<?> c = (Class<?>)type;
            final Integer priorityInteger;
            synchronized (priorities) {
              priorityInteger = priorities.get(c);
            }
            if (priorityInteger == null) {
              final Priority priority = c.getAnnotation(Priority.class);
              if (priority != null) {
                returnValue = priority.value();
              }
            } else {
              returnValue = priorityInteger.intValue();
              break;
            }
          }
        }
      }
      return returnValue;      
    }
    
  }

}
