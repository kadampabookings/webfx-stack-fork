// File managed by WebFX (DO NOT EDIT MANUALLY)

module webfx.stack.authn.server.gateway.google.plugin {

    // Direct dependencies modules
    requires webfx.platform.ast;
    requires webfx.platform.async;
    requires webfx.stack.authn;
    requires webfx.stack.authn.server.gateway;

    // Exported packages
    exports dev.webfx.stack.authn.server.gateway.spi.impl.google;

    // Provided services
    provides dev.webfx.stack.authn.server.gateway.spi.ServerAuthenticationGateway with dev.webfx.stack.authn.server.gateway.spi.impl.google.GoogleServerAuthenticationGateway;

}