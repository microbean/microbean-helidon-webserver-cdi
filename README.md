# microBean Helidon WebServer CDI

[![Build Status](https://travis-ci.org/microbean/microbean-helidon-webserver-cdi.svg?branch=master)](https://travis-ci.org/microbean/microbean-helidon-webserver-cdi)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-helidon-webserver-cdi/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-helidon-webserver-cdi)

# Overview

This project consists of a [CDI portable
extension](http://docs.jboss.org/cdi/spec/2.0/cdi-spec.html#spi) that
embeds a [Helidon
`WebServer`](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/WebServer.html)
into a CDI environment and [starts
it](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/WebServer.html#start--).

The portable extension will look for any
[`Service`](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/Service.html)
class it can find that [is
detectable](http://docs.jboss.org/cdi/spec/2.0/cdi-spec.html#packaging_deployment).
It will instantiate it and call its
[`update(Routing.Rules)`](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/Service.html#update-io.helidon.webserver.Routing.Rules-)
method prior to starting the `WebServer`.

# Why Would I Want To Use This?

You may be thinking: why would I want to put a web server in a CDI container?
This is probably because you are thinking of CDI as a component of a Java EE
application server.  But CDI 2.0 is usable in Java SE.

If you start the CDI container from your own `main` method like so:

```
try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
  // deliberately empty
}
```

...or, equivalently, use the `microbean-main` project's `Main` class to do this
for you, then a CDI container comes up and goes down.  In between all of your
CDI beans are instantiated and wired appropriately.

So don't think of a CDI container as something that lives inside a web server or
alongside one.  Think instead of a web server as just another Java component in
the CDI ecosystem.

At this point you can probably see that if Helidon SE is just another component
in the CDI ecosystem, and your `Service` implementations are just another
component in the CDI ecosystem, then the CDI container can instantiate and
autowire all of them equivalently.

This gives you all the benefits of Helidon SE and all the benefits of CDI at the
expense of roughly 50 milliseconds of additional startup time while letting you
write a simple Java SE application from a component-oriented perspective.

# Simple Example

Suppose you write a
[`Service`](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/Service.html)
like this:

```
import io.helidon.webserver.Service;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;

@ApplicationScoped
public class MyService implements Service {

  private final SomeClientForSomething client;

  @Inject
  public MyService(final SomeClientForSomething client) {
    super();
    this.client = Objects.requireNonNull(client);
  }

  @Override
  public void update(final Routing.Rules rules) {
    rules.get("/foo", this::handleFoo); // handle GET requests with the handleFoo method
  }
  
  private void handleFoo(final ServerRequest request,
                         final ServerResponse response) {
    // You can use this.client here.
  }

}
```

The presence of this class on the classpath will cause the CDI
container to create an instance of `MyService`, supply it with an
instance of `SomeClientForSomething` assuming that this bean is
present in the CDI container, and call its [`update`
method](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/Service.html#update-io.helidon.webserver.Routing.Rules-).
A [Helidon
`WebServer`](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/WebServer.html)
will then be created and
[started](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/WebServer.html#start--)
using [default
configuration](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/ServerConfiguration.html).
The webserver will stop when it receives a `CTRL-C` signal.

# Customization

All intermediate objects required to create and start a `WebServer`
are treated as CDI beans as well, with the end user's versions of
those beans (if any) being preferred over the built-in defaults.  So,
for example, if you were to supply your own `WebServer` producer
method, that `WebServer` instance would be used instead.

If you do nothing, a default
[`Config.Builder`](https://helidon.io/docs/latest/apidocs/io/helidon/config/Config.Builder.html)
will be created to build a
[`Config`](https://helidon.io/docs/latest/apidocs/io/helidon/config/Config.html)
object.  The `Config` object so built will be used to create a
[`ServerConfiguration.Builder`](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/ServerConfiguration.Builder.html),
which, in turn, will be used to build a
[`ServerConfiguration`](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/ServerConfiguration.html).
This general pattern applies all the way up to the
[`WebServer`](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/WebServer.html)
itself (built by a
[`WebServer.Builder`](https://helidon.io/docs/latest/apidocs/io/helidon/webserver/WebServer.Builder.html)).

Each of these objects is treated as a CDI bean.  If the CDI bean in
question does not exist, then a "normal" instance of the object in
question is registered as a bean and instantiated appropriately by the
CDI container.

In the case of such default instantiations, you can customize them if
you want.  For example, maybe you don't want to actually supply an
alternate [`Config`]() implementation&mdash;the default one is just
fine&mdash;but you _do_ want to slightly change how it's made.  To
perform simple customization of this sort, you declare an observer
method that receives the relevant `Builder` object as its observed
parameter.  For example, somewhere in your CDI application, you can
write:

```
private static final void customizeConfigBuilder(@Observes final Config.Builder configBuilder) {
  builder.disableSystemPropertiesSource(); // or whatever
}
```

The `Builder` so customized will be used internally by this project to
produce `Config` objects (as CDI beans) that may be required by other
Helidon objects.
