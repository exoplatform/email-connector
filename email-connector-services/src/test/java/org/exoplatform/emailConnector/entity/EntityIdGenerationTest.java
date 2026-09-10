/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.meeds.common.persistence.PortableSequence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;

/**
 * The one property of this add-on's entities that no database in the test suite can
 * show: that a generated id is generated the way the platform generates ids.
 * <p>
 * {@code GenerationType.AUTO} resolves, since Hibernate 6, to a
 * {@code SequenceStyleGenerator}; on a vendor with sequences -- HSQLDB, which every
 * test here runs on, with {@code ddl-auto=create-drop} creating the sequence itself --
 * that is a real sequence and everything passes. On MySQL, which has none, the same
 * generator falls back to a physical generator TABLE of that name that no Liquibase
 * changeset ever creates, and the first insert fails with "Table
 * 'SEQ_EMAIL_FOLDER_ID' doesn't exist". So the defect is invisible to the whole suite
 * and visible on every MySQL deployment -- which is exactly what happened to
 * EMAIL_FOLDER. {@link PortableSequence} is the platform's answer: identity on a MySQL
 * dialect, the named sequence elsewhere, matching what the changelog creates on each
 * vendor ({@code autoIncrement} on MySQL, {@code createSequence} on the rest).
 * <p>
 * The check therefore runs on the annotations, over the entities named by
 * {@code jpa-entities.idx} -- the same index the platform's entity scanner reads, so
 * an entity that is registered is an entity that is checked. An entity with a natural
 * key generates nothing and is fine; what is refused is a generated id declared by
 * anything other than {@code @PortableSequence}.
 */
class EntityIdGenerationTest {

  private static final String ENTITIES_IDX_PATH = "jpa-entities.idx";

  /**
   * Every {@code @Id} declared by a registered entity either generates nothing, or
   * generates through {@link PortableSequence} -- never {@code @GeneratedValue} or
   * {@code @SequenceGenerator}, which are portable in name only.
   */
  @Test
  void generatedIdsUsePortableSequence() throws IOException, ClassNotFoundException {
    List<String> failures = new ArrayList<>();
    List<Class<?>> entities = registeredEntities();
    assertTrue(entities.size() >= 8, "expected the entity index to list this add-on's entities, found " + entities.size());

    for (Class<?> entity : entities) {
      for (Field field : entity.getDeclaredFields()) {
        if (!field.isAnnotationPresent(Id.class)) {
          continue;
        }
        String id = entity.getSimpleName() + "#" + field.getName();
        if (field.isAnnotationPresent(GeneratedValue.class)) {
          failures.add(id + " carries @GeneratedValue; use @PortableSequence");
        }
        if (field.isAnnotationPresent(SequenceGenerator.class)) {
          failures.add(id + " carries @SequenceGenerator; use @PortableSequence");
        }
      }
    }
    if (!failures.isEmpty()) {
      fail("id generation must go through @PortableSequence: " + String.join(", ", failures));
    }
  }

  /**
   * The entity that motivated this test, pinned by name: the custom-folder registry
   * generates its id, and generates it portably, under the sequence name the changelog
   * creates.
   */
  @Test
  void emailFolderEntityGeneratesItsIdPortably() throws NoSuchFieldException {
    Field id = EmailFolderEntity.class.getDeclaredField("id");
    PortableSequence portableSequence = id.getAnnotation(PortableSequence.class);
    assertNotNull(portableSequence, "EmailFolderEntity#id must carry @PortableSequence");
    assertEquals("SEQ_EMAIL_FOLDER_ID",
                 portableSequence.name(),
                 "the sequence name must be the one emailConnector-rdbms.db.changelog-master.xml creates");
  }

  /**
   * The non-blank lines of {@code jpa-entities.idx} -- the platform's own registration
   * list, so reading it is how this test stays true of entities added after it was
   * written.
   */
  private List<String> indexLines() throws IOException {
    List<String> lines = new ArrayList<>();
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(ENTITIES_IDX_PATH)) {
      assertNotNull(in, ENTITIES_IDX_PATH + " must be on the classpath");
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.isBlank()) {
            lines.add(line.trim());
          }
        }
      }
    }
    return lines;
  }

  /**
   * Loads the indexed classes and keeps the JPA entities among them -- the index also
   * lists {@code @Converter} classes, which have no ids.
   */
  private List<Class<?>> registeredEntities() throws IOException, ClassNotFoundException {
    List<Class<?>> entities = new ArrayList<>();
    for (String className : indexLines()) {
      Class<?> clazz = Class.forName(className);
      if (clazz.isAnnotationPresent(Entity.class)) {
        entities.add(clazz);
      }
    }
    return entities;
  }

}
