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
expense of roughly 50 milliseconds of additional startup time.
