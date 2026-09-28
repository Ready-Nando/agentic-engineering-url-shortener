package com.acme.links.api;

import com.acme.links.service.LinkService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class RedirectController {

    private final LinkService links;

    RedirectController(LinkService links) {
        this.links = links;
    }

    @GetMapping({"r/{code}", "/go/{code}"})
    public String redirect(@PathVariable String code) {
        return "redirect:" + links.resolve(code).targetUrl();
    }
}
