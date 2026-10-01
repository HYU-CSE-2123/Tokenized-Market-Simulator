package com.pricetrack.exchange.ai.skill;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
@ConfigurationProperties("app.ai.skills")
public record SkillProperties(@DefaultValue("false") boolean enabled) {}
