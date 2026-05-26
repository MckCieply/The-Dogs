package com.thedogs.modules.notes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thedogs.modules.auth.TokenService;
import com.thedogs.modules.clients.Client;
import com.thedogs.modules.clients.ClientRepository;
import com.thedogs.modules.dogs.Dog;
import com.thedogs.modules.dogs.DogRepository;
import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.RoleRepository;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import java.util.Set;
import java.util.UUID;
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
class NoteIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ClientRepository clientRepository;
  @Autowired private DogRepository dogRepository;
  @Autowired private NoteRepository noteRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TokenService tokenService;

  private String accessToken;
  private UUID dogId;

  @BeforeEach
  void setUp() {
    Role trainerRole = roleRepository.findByName("ROLE_TRAINER").orElseThrow();
    User trainer =
        User.builder()
            .email("trainer2@example.com")
            .passwordHash(passwordEncoder.encode("pass"))
            .roles(Set.of(trainerRole))
            .build();
    trainer = userRepository.save(trainer);

    Client client = Client.builder().trainer(trainer).name("Bob Jones").build();
    client = clientRepository.save(client);

    Dog dog = Dog.builder().client(client).name("Buddy").build();
    dog = dogRepository.save(dog);
    dogId = dog.getId();

    Note note =
        Note.builder().dog(dog).trainer(trainer).title("First session").body("Went well.").build();
    noteRepository.save(note);

    accessToken = tokenService.generateAccessToken(trainer);
  }

  @Test
  void listNotes_withValidToken_returns200WithNotes() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/dogs/{dogId}/notes", dogId)
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data[0].title").value("First session"));
  }

  @Test
  void listNotes_withoutToken_returns401() throws Exception {
    mockMvc.perform(get("/api/v1/dogs/{dogId}/notes", dogId)).andExpect(status().isUnauthorized());
  }
}
