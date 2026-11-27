package com.asialjim.cache4j;

import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Import(Cache4jSpring.class)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableCache4j {
}