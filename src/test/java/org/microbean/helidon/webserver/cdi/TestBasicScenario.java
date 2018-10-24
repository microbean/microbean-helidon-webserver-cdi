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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import java.util.Objects;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;
import javax.enterprise.context.RequestScoped;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.se.SeContainer;
import javax.enterprise.inject.se.SeContainerInitializer;

import javax.inject.Inject;

import io.helidon.config.Config;

import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import io.helidon.webserver.WebServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@ApplicationScoped
public class TestBasicScenario {

  
  /*
   * Test boilerplate.
   */

  
  private SeContainer cdiContainer;

  public TestBasicScenario() {
    super();
  }

  @Before
  public void startCdiContainer() {
    final SeContainerInitializer initializer = SeContainerInitializer.newInstance();
    assertNotNull(initializer);    
    this.cdiContainer = initializer.initialize();
  }

  @After
  public void shutDownCdiContainer() {
    if (this.cdiContainer != null) {
      this.cdiContainer.close();
    }
  }

  @Test
  public void test() {

  }


  /*
   * Test behaving as a WebServer client.
   */
  
  
  private void onStartup(@Observes @Initialized(ApplicationScoped.class) final Object event,
                         WebServer webServer)
    throws InterruptedException, IOException, ExecutionException, URISyntaxException {
    try {
      assertNotNull(event);
      assertNotNull(webServer);
      // Note that the webServer has already been started; this merely
      // gets a handle to the already-running start task.
      final CompletionStage<WebServer> startTask = webServer.start();
      assertNotNull(startTask);
      webServer = startTask.toCompletableFuture().get();
      assertNotNull(webServer);
      final int port = webServer.port();
      assertTrue(port >= 0);
      final URL hoopy = new URI("http", null, "0.0.0.0", port, "/hoopy", null, null).toURL();
      assertNotNull(hoopy);
      final Object content = hoopy.getContent();
      assertTrue(content instanceof InputStream);
      try (final BufferedReader reader = new BufferedReader(new InputStreamReader((InputStream)content, "UTF-8"))) {
        assertEquals("frood", reader.readLine());
      }
    } finally {
      if (webServer != null) {
        webServer.shutdown();
      }
    }
  }


  /*
   * Example user code exercised by test.
   */
  

  @ApplicationScoped
  private static final class MyService implements Service {

    private Thing thing;

    @Inject
    private MyService(final Thing thing) {
      super();
      this.thing = Objects.requireNonNull(thing);
    }
    
    @Override
    @SuppressWarnings("rawtypes")
    public void update(final Routing.Rules rules) {
      assertNotNull(rules);
      System.out.println("*** " + this.getClass().getName() + " loaded; updating rules");
      rules.get("/hoopy", this::hoopy);
    }

    private void hoopy(final ServerRequest request, final ServerResponse response) {
      System.out.println("*** getting content for hoopy on thread " + Thread.currentThread());
      assertNotNull(this.thing.toString()); // proves request scope works
      response.send("frood");
    }

    private static void configureRoutingBuilder(@Observes final Routing.Builder routingBuilder) {
      assertNotNull(routingBuilder);
      assertFalse(routingBuilder.getClass().isSynthetic());
      routingBuilder.get("/frood", MyService::frood); // set up a static handler
    }
    
    private static void frood(final ServerRequest request, final ServerResponse response) {
      System.out.println("*** getting content for frood on thread " + Thread.currentThread());
      response.send("hoopy"); // proves static methods as handlers works
    }

  }

  @ApplicationScoped
  private static class MyService2 implements Service {

    private final Config config;

    @Inject
    private MyService2(final Config config) {
      super();
      this.config = config;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void update(final Routing.Rules rules) {
      assertNotNull(this.config); // proves injection works
      assertNotNull(rules);
      assertNotNull(rules.toString());
      System.out.println("*** " + this.getClass().getName() + " loaded; updating rules");
    }

  }

  @RequestScoped
  private static class Thing {

    private Thing() {
      super();
    }

  }

}
