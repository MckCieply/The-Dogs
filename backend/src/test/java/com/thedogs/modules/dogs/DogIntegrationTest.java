package com.thedogs.modules.dogs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thedogs.modules.auth.TokenService;
import com.thedogs.modules.clients.Client;
import com.thedogs.modules.clients.ClientRepository;
import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.RoleRepository;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DogIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ClientRepository clientRepository;
  @Autowired private DogRepository dogRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TokenService tokenService;

  private String accessToken;

  @BeforeEach
  void setUp() {
    Role trainerRole = roleRepository.findByName("ROLE_TRAINER").orElseThrow();
    User trainer =
        User.builder()
            .email("trainer@example.com")
            .passwordHash(passwordEncoder.encode("pass"))
            .roles(Set.of(trainerRole))
            .build();
    trainer = userRepository.save(trainer);

    Client client = Client.builder().trainer(trainer).name("Alice Smith").build();
    client = clientRepository.save(client);

    Dog dog = Dog.builder().client(client).name("Rex").breed("Labrador").build();
    dogRepository.save(dog);

    accessToken = tokenService.generateAccessToken(trainer);
  }

  @Test
  void listDogs_withoutToken_returns401() throws Exception {
    mockMvc.perform(get("/api/v1/dogs")).andExpect(status().isUnauthorized());
  }

  @Test
  void listDogs_withValidToken_returns200WithDogs() throws Exception {
    mockMvc
        .perform(get("/api/v1/dogs").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data[0].name").value("Rex"));
  }
}
