package com.splitmates.repo;

import com.splitmates.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByPhoneAndDeletedFalse(String phone);

    Optional<User> findByEmailAndDeletedFalse(String email);
}
