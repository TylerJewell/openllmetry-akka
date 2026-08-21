package io.akka.openllmetry.domain;

import java.util.List;

/** One message sent to the model, already in role-and-parts form. */
public record Message(String role, List<Part> parts) {}
