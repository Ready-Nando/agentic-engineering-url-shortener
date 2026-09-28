package com.example.sdlc.scenario;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.example.sdlc.human.HumanGateway;
import com.example.sdlc.human.HumanRequest;
import com.example.sdlc.human.HumanResponse;

/** Terminal prompt for a real human. Typing "later" (or closing input) defers the decision and pauses the run. */
public final class InteractiveReviewer implements HumanGateway {

    private final BufferedReader in;
    private final PrintStream out;
    private final String reviewer;

    public InteractiveReviewer(BufferedReader in, PrintStream out, String reviewer) {
        this.in = in;
        this.out = out;
        this.reviewer = reviewer;
    }

    @Override
    public Optional<HumanResponse> respond(HumanRequest request) {
        out.println();
        out.println("  ┌─ HUMAN CHECKPOINT " + request.id() + " (" + request.kind() + ", task " + request.taskId() + ")");
        out.println("  │ " + request.title());
        request.details().forEach(detail -> out.println("  │   " + detail));
        return request.kind() == HumanRequest.Kind.CLARIFICATION ? clarify(request) : decide(request);
    }

    private Optional<HumanResponse> decide(HumanRequest request) {
        String choice = ask("  └─ [a]pprove, [r]eject, request [c]hanges, or 'later': ");
        if (choice == null || choice.equalsIgnoreCase("later")) {
            return Optional.empty();
        }
        return switch (choice.toLowerCase()) {
            case "a", "approve" -> Optional.of(HumanResponse.approve(reviewer, orEmpty(ask("     comment: "))));
            case "r", "reject" -> Optional.of(HumanResponse.reject(reviewer, orEmpty(ask("     reason: "))));
            case "c", "changes" -> {
                String target = orEmpty(ask("     task to change [" + request.taskId() + "]: "));
                String comment = orEmpty(ask("     requested change: "));
                yield Optional.of(HumanResponse.requestChanges(reviewer, comment, target.isBlank() ? request.taskId() : target));
            }
            default -> decide(request);
        };
    }

    private Optional<HumanResponse> clarify(HumanRequest request) {
        Map<String, String> answers = new LinkedHashMap<>();
        for (HumanRequest.Question question : request.questions()) {
            out.println("  │ " + question.id() + ": " + question.text());
            question.options().forEach(o -> out.println("  │     " + o.id() + " - " + o.label()
                    + (o.consequence() == null || o.consequence().isBlank() ? "" : " (" + o.consequence() + ")")));
            String answer = ask("  │   answer" + (question.freeTextAllowed() ? " (option id or text)" : " (option id)") + ", or 'later': ");
            if (answer == null || answer.equalsIgnoreCase("later")) {
                return Optional.empty();
            }
            answers.put(question.id(), answer.strip());
        }
        return Optional.of(HumanResponse.answer(reviewer, answers));
    }

    private String ask(String prompt) {
        out.print(prompt);
        out.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
