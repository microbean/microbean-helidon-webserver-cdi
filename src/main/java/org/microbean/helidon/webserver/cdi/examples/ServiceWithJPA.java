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
package org.microbean.helidon.webserver.cdi.examples;

import java.util.Objects;

import javax.enterprise.context.ApplicationScoped;

import javax.inject.Inject;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import javax.sql.DataSource;

import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;

@ApplicationScoped
public class ServiceWithJPA implements Service {

  @PersistenceContext(unitName = "test")
  private EntityManager entityManager;

  public ServiceWithJPA() {
    super();
  }
  
  @Override
  @SuppressWarnings("rawtypes")
  public void update(final Routing.Rules rules) {
    Objects.requireNonNull(rules).get("/person/{id}", this::getPerson);
  }

  private void getPerson(final ServerRequest request, final ServerResponse response) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(response);

    
  }
  
}
