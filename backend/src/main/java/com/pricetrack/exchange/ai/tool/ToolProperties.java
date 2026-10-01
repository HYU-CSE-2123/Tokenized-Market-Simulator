package com.pricetrack.exchange.ai.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Read tools do not depend on the RAG switch, AI database or provider credentials. */
@ConfigurationProperties("app.ai.tools")
public record ToolProperties(@DefaultValue("false") boolean enabled) {}
