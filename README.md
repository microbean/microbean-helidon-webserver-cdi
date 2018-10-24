# microBean Helidon WebServer CDI

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
