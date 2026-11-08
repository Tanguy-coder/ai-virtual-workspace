package dev.aiw.events;

/** The object an event is about, e.g. ("agent", id). */
public record Subject(String kind, String id) {}
