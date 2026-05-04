package com.thedogs.modules.notes.mapper;

import com.thedogs.modules.notes.Note;
import com.thedogs.modules.notes.dto.NoteDto;
import com.thedogs.modules.notes.dto.NoteRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface NoteMapper {

  @Mapping(target = "dogId", source = "dog.id")
  @Mapping(target = "trainerId", source = "trainer.id")
  NoteDto toDto(Note note);

  @Mapping(target = "dog", ignore = true)
  @Mapping(target = "trainer", ignore = true)
  Note toEntity(NoteRequest request);

  @Mapping(target = "dog", ignore = true)
  @Mapping(target = "trainer", ignore = true)
  void updateEntity(NoteRequest request, @MappingTarget Note note);
}
