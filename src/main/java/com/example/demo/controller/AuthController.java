package com.example.demo.controller;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.dto.AuthResponse;
import com.example.demo.dto.LoginRequest;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.JwtUtils;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@Valid @RequestBody LoginRequest loginRequest) {
    	System.out.println("DEBUG LOGIN ATTEMPT: username=['" + loginRequest.getUsername() + "'], password=['" + loginRequest.getPassword() + "']");
        
//    	User dbUser = userRepository.findByUsername(loginRequest.getUsername()).orElse(null);
//        if (dbUser == null) {
//            System.err.println(">>> DIAGNOSTIC: User NOT FOUND in database: " + loginRequest.getUsername());
//        } else {
//            System.out.println(">>> DIAGNOSTIC: Found User: " + dbUser.getUsername());
//            System.out.println(">>> DIAGNOSTIC: dbUser.getPassword() returns: [" + dbUser.getPassword() + "]");
//            System.out.println(">>> DIAGNOSTIC: dbUser.getPasswordHash() returns: [" + dbUser.getPasswordHash() + "]");
//            System.out.println(">>> DIAGNOSTIC: Raw password received: [" + loginRequest.getPassword() + "]");
//            
//            if (dbUser != null) {
//                // Self-healing: encode Admin@12345 using the runtime BCryptPasswordEncoder bean
//                if (!passwordEncoder.matches(loginRequest.getPassword(), dbUser.getPassword())) {
//                    System.out.println(">>> AUTO-FIXING PASSWORD IN DATABASE FOR: " + dbUser.getUsername());
//                    dbUser.setPasswordHash(passwordEncoder.encode(loginRequest.getPassword()));
//                    userRepository.save(dbUser);
//                    System.out.println(">>> PASSWORD UPDATED TO MATCH INPUT SUCCESSFULLY.");
//                }
//            }           
//        }
        // -------------------------
    	try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(),
                            loginRequest.getPassword()
                    )
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
            User user = (User) authentication.getPrincipal();
            String jwt = jwtUtils.generateToken(user);

            return ResponseEntity.ok(AuthResponse.builder()
                    .token(jwt)
                    .type("Bearer")
                    .id(user.getId())
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .fullName(user.getFullName())
                    .role(user.getRole().name())
                    .build());

        } catch (BadCredentialsException ex) {
            // Return a clear, explicit bad credentials response
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                        "status", 401,
                        "error", "Unauthorized",
                        "message", "Invalid username or password entered"
                    ));
        }
    }
}