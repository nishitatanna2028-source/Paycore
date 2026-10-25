package com.paycore.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled so the webhook dispatcher can poll for due events. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
