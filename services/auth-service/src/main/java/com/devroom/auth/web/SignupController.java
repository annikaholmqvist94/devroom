package com.devroom.auth.web;

import com.devroom.auth.application.SignupService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/signup")
public class SignupController {

    private final SignupService service;

    public SignupController(SignupService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest req) {
        SignupService.Result result = service.signup(req.email(), req.password());
        SignupResponse body = new SignupResponse(result.userId());
        return ResponseEntity
                .created(URI.create("/users/" + result.userId()))
                .body(body);
    }
}
