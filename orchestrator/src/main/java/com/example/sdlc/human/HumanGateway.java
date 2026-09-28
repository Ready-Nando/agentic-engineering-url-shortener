package com.example.sdlc.human;

import java.util.Optional;

/**
 * Where human decisions come from. An empty result means "not answered now": the run keeps doing other ready
 * work and pauses safely once nothing else can progress, to be resumed when the answer is supplied.
 */
public interface HumanGateway {

    Optional<HumanResponse> respond(HumanRequest request);

    /** Never answers: every checkpoint pauses the run (asynchronous review via the CLI). */
    HumanGateway DEFERRED = request -> Optional.empty();
}
