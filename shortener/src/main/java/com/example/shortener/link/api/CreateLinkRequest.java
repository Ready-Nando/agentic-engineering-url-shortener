package com.example.shortener.link.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * @param alias optional custom code; its format and reserved words are checked by
 *              {@link com.example.shortener.link.AliasPolicy}
 */
public record CreateLinkRequest(
        @NotBlank @Size(max = 2048) String url,
        @Nullable String alias) {
}
