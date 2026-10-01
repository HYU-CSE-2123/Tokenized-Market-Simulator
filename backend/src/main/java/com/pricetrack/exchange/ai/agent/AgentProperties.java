package com.pricetrack.exchange.ai.agent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("app.ai.agent")
public record AgentProperties(@DefaultValue("false") boolean enabled) {}
