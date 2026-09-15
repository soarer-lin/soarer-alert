package com.soarer.alert.security;

import com.soarer.alert.repository.AuthUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * AuthUserDetailsService 认证适配组件。
 */
@Service
public class AuthUserDetailsService implements UserDetailsService {

    private final AuthUserRepository userRepository;

    public AuthUserDetailsService(AuthUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .map(AuthUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
