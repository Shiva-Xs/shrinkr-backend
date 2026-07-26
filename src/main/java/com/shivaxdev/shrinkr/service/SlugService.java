package com.shivaxdev.shrinkr.service;

import com.shivaxdev.shrinkr.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
@RequiredArgsConstructor
public class SlugService {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int SLUG_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();

    private final LinkRepository linkRepository;

    public String generateUniqueSlug() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String slug = generateSlug();
            if (!linkRepository.existsBySlug(slug)) {
                return slug;   
            }

        }
        throw new IllegalStateException("Failed to generate a unique slug after 5 attempts");
    }

    private String generateSlug() {
        StringBuilder sb = new StringBuilder(SLUG_LENGTH);
        for (int i = 0; i < SLUG_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
