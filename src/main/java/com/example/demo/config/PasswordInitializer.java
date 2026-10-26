package com.example.demo.config;

import com.example.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PasswordInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        userRepository.findByUsername("admin").ifPresent(user -> {
            String rawPassword = "Admin@12345";
            String freshHash = passwordEncoder.encode(rawPassword);
            user.setPasswordHash(freshHash);
            userRepository.save(user);

            System.out.println("=================================================================");
            System.out.println("PASSWORD INITIALIZER: Reset admin password to 'Admin@12345'");
            System.out.println("Generated Hash: " + freshHash);
            System.out.println("Immediate Self-Test Match: " + passwordEncoder.matches(rawPassword, freshHash));
            System.out.println("=================================================================");
        });
    }
}