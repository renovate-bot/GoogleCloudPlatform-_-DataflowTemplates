/*
 * Copyright (C) 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.spanner.migrations.convertors;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.ByteArray;
import com.google.cloud.Date;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Value;
import com.google.cloud.teleport.v2.spanner.ddl.Ddl;
import com.google.cloud.teleport.v2.spanner.ddl.Table;
import com.google.cloud.teleport.v2.spanner.migrations.constants.Constants;
import com.google.cloud.teleport.v2.spanner.migrations.exceptions.ChangeEventConvertorException;
import com.google.cloud.teleport.v2.spanner.type.Type;
import com.google.common.truth.Truth;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public class ChangeEventSpannerConvertorTest {
  static Ddl getTestDdl() {
    Ddl ddl =
        Ddl.builder()
            .createTable("Users")
            .column("first_name")
            .string()
            .max()
            .endColumn()
            .column("last_name")
            .string()
            .size(5)
            .endColumn()
            .column("age")
            .int64()
            .endColumn()
            .column("bool_field")
            .bool()
            .endColumn()
            .column("bool_field2")
            .bool()
            .endColumn()
            .column("int64_field")
            .int64()
            .endColumn()
            .column("float64_field")
            .float64()
            .endColumn()
            .column("string_field")
            .string()
            .max()
            .endColumn()
            .column("json_field")
            .json()
            .endColumn()
            .column("bytes_field")
            .bytes()
            .max()
            .endColumn()
            .column("timestamp_field")
            .timestamp()
            .endColumn()
            .column("timestamp_field2")
            .timestamp()
            .endColumn()
            .column("date_field")
            .date()
            .endColumn()
            .column("date_field2")
            .date()
            .endColumn()
            .primaryKey()
            .asc("first_name")
            .desc("last_name")
            .end()
            .endTable()
            .build();
    return ddl;
  }

  static JSONObject getTestChangeEvent(String tableName) {
    JSONObject changeEvent = new JSONObject();
    changeEvent.put("first_name", "A");
    changeEvent.put("last_name", "B");
    changeEvent.put("age", "10");
    changeEvent.put("bool_field", "true");
    changeEvent.put("bool_field2", true);
    changeEvent.put("int64_field", "2344");
    changeEvent.put("float64_field", "2344.34");
    changeEvent.put("string_field", "testtest");
    changeEvent.put("json_field", "{\"key1\": \"value1\", \"key2\": \"value2\"}");
    changeEvent.put("bytes_field", "7835383030");
    changeEvent.put(
        "timestamp_field", Timestamp.of(java.sql.Timestamp.valueOf("2020-12-30 4:12:12")));
    changeEvent.put(
        "timestamp_field2", Timestamp.of(java.sql.Timestamp.valueOf("2020-12-30 4:12:12.1")));
    changeEvent.put("date_field", "2020-12-30T00:00:00Z");
    changeEvent.put("date_field2", "2020-12-30");
    changeEvent.put(Constants.EVENT_TABLE_NAME_KEY, tableName);
    return changeEvent;
  }

  static Map<String, Value> getExpectedMapForTestChangeEvent() {
    Map<String, Value> expected =
        new HashMap<String, Value>() {
          {
            put("first_name", Value.string("A"));
            put("last_name", Value.string("B"));
            put("age", Value.int64(10));
            put("bool_field", Value.bool(true));
            put("bool_field2", Value.bool(true));
            put("int64_field", Value.int64(2344));
            put("float64_field", Value.float64(2344.34));
            put("string_field", Value.string("testtest"));
            put("json_field", Value.string("{\"key1\": \"value1\", \"key2\": \"value2\"}"));
            put("bytes_field", Value.bytes(ByteArray.copyFrom(new byte[] {120, 53, 56, 48, 48})));
            // Added expected time in localtime
            put(
                "timestamp_field",
                Value.timestamp(Timestamp.of(java.sql.Timestamp.valueOf("2020-12-30 4:12:12"))));
            put(
                "timestamp_field2",
                Value.timestamp(Timestamp.of(java.sql.Timestamp.valueOf("2020-12-30 4:12:12.1"))));
            put("date_field", Value.date(Date.parseDate("2020-12-30")));
            put("date_field2", Value.date(Date.parseDate("2020-12-30")));
          }
        };
    return expected;
  }

  public static JsonNode parseChangeEvent(String json) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
      return mapper.readTree(json);
    } catch (IOException e) {
      // No action. Return null.
    }
    return null;
  }

  static Ddl getTestDdlForPrimaryKeyTest() {

    Ddl ddl =
        Ddl.builder()
            .createTable("Users2")
            .column("first_name")
            .string()
            .max()
            .endColumn()
            .column("last_name")
            .string()
            .size(5)
            .endColumn()
            .column("age")
            .int64()
            .endColumn()
            .column("bool_field")
            .bool()
            .endColumn()
            .column("bool_field2")
            .bool()
            .endColumn()
            .column("int64_field")
            .int64()
            .endColumn()
            .column("float32_field")
            .float32()
            .endColumn()
            .column("float64_field")
            .float64()
            .endColumn()
            .column("string_field")
            .string()
            .max()
            .endColumn()
            .column("json_field")
            .json()
            .endColumn()
            .column("bytes_field")
            .bytes()
            .max()
            .endColumn()
            .column("timestamp_field")
            .timestamp()
            .endColumn()
            .column("timestamp_field2")
            .timestamp()
            .endColumn()
            .column("date_field")
            .date()
            .endColumn()
            .column("date_field2")
            .date()
            .endColumn()
            .primaryKey()
            .asc("first_name")
            .desc("last_name")
            .asc("age")
            .asc("bool_field")
            .asc("bool_field2")
            .asc("int64_field")
            .asc("float64_field")
            .asc("string_field")
            .asc("bytes_field")
            .asc("timestamp_field")
            .asc("timestamp_field2")
            .asc("date_field")
            .asc("date_field2")
            .end()
            .endTable()
            .build();
    return ddl;
  }

  @Test
  public void mutationFromEventBasic() throws Exception {
    Ddl ddl = getTestDdl();
    JSONObject changeEvent = getTestChangeEvent("Users");
    JsonNode ce = parseChangeEvent(changeEvent.toString());
    Table table = ddl.table("Users");

    List<String> colNames =
        Arrays.asList(
            "first_name",
            "last_name",
            "age",
            "bool_field",
            "bool_field2",
            "int64_field",
            "float64_field",
            "string_field",
            "json_field",
            "bytes_field",
            "timestamp_field",
            "timestamp_field2",
            "date_field",
            "date_field2");
    Set<String> keyNames = new HashSet<>(Arrays.asList("first_name", "last_name"));
    Mutation mutation =
        ChangeEventSpannerConvertor.mutationFromEvent(table, ce, colNames, keyNames);
    Map<String, Value> actual = mutation.asMap();
    Map<String, Value> expected = getExpectedMapForTestChangeEvent();

    assertThat(actual, is(expected));
  }

  @Test
  public void canConvertChangeEventToPrimaryKey() throws Exception {
    Ddl ddl = getTestDdlForPrimaryKeyTest();
    JSONObject changeEvent = getTestChangeEvent("Users2");
    JsonNode ce = parseChangeEvent(changeEvent.toString());
    Key key =
        ChangeEventSpannerConvertor.changeEventToPrimaryKey(
            "Users2", ddl, ce, /* convertNameToLowerCase= */ true);
    Iterable<Object> keyParts = key.getParts();
    ArrayList<Object> expectedKeyParts = new ArrayList<>();
    expectedKeyParts.add("A");
    expectedKeyParts.add("B");
    expectedKeyParts.add(Long.valueOf(10));
    expectedKeyParts.add(Boolean.valueOf(true));
    expectedKeyParts.add(Boolean.valueOf(true));
    expectedKeyParts.add(Long.valueOf(2344));
    expectedKeyParts.add(Double.valueOf(2344.34));
    expectedKeyParts.add("testtest");
    expectedKeyParts.add(ByteArray.copyFrom(new byte[] {120, 53, 56, 48, 48}));
    expectedKeyParts.add(Timestamp.of(java.sql.Timestamp.valueOf("2020-12-30 4:12:12")));
    expectedKeyParts.add(Timestamp.of(java.sql.Timestamp.valueOf("2020-12-30 4:12:12.1")));
    expectedKeyParts.add(Date.parseDate("2020-12-30"));
    expectedKeyParts.add(Date.parseDate("2020-12-30"));

    assertThat(keyParts, is(expectedKeyParts));
  }

  @Test(expected = ChangeEventConvertorException.class)
  public void cannotConvertChangeEventWithMissingKeyColToPrimaryKey() throws Exception {
    Ddl ddl = getTestDdlForPrimaryKeyTest();
    JSONObject changeEvent = getTestChangeEvent("Users2");
    changeEvent.remove("last_name");
    JsonNode ce = parseChangeEvent(changeEvent.toString());
    Key key =
        ChangeEventSpannerConvertor.changeEventToPrimaryKey(
            "Users2", ddl, ce, /* convertNameToLowerCase= */ true);
    // Expect an exception since the event is missing a primary key
  }

  @Test(expected = ChangeEventConvertorException.class)
  public void cannotConvertChangeEventWithInvalidTimestampToPrimaryKey() throws Exception {
    Ddl ddl = getTestDdlForPrimaryKeyTest();
    JSONObject changeEvent = getTestChangeEvent("Users2");
    changeEvent.put("timestamp_field", "2020-12-asdf");
    JsonNode ce = parseChangeEvent(changeEvent.toString());
    Key key =
        ChangeEventSpannerConvertor.changeEventToPrimaryKey(
            "Users2", ddl, ce, /* convertNameToLowerCase= */ true);
    // Expect an exception since the event has invalid timestamp
  }

  @Test(expected = ChangeEventConvertorException.class)
  public void cannotConvertChangeEventWithInvalidDateToPrimaryKey() throws Exception {
    Ddl ddl = getTestDdlForPrimaryKeyTest();
    JSONObject changeEvent = getTestChangeEvent("Users2");
    changeEvent.put("date_field", "asdf");
    JsonNode ce = parseChangeEvent(changeEvent.toString());
    Key key =
        ChangeEventSpannerConvertor.changeEventToPrimaryKey(
            "Users2", ddl, ce, /* convertNameToLowerCase= */ true);
    // Expect an exception since the event has invalid timestamp
  }

  @Test(expected = ChangeEventConvertorException.class)
  public void cannotConvertChangeEventWithInvalidInt64ToPrimaryKey() throws Exception {
    Ddl ddl = getTestDdlForPrimaryKeyTest();
    JSONObject changeEvent = getTestChangeEvent("Users2");
    changeEvent.put("int64_field", "asdfas");
    JsonNode ce = parseChangeEvent(changeEvent.toString());
    Key key =
        ChangeEventSpannerConvertor.changeEventToPrimaryKey(
            "Users2", ddl, ce, /* convertNameToLowerCase= */ true);
    // Expect an exception since the event has invalid timestamp
  }

  @Test(expected = ChangeEventConvertorException.class)
  public void cannotConvertChangeEventWithInvalidFloat64ToPrimaryKey() throws Exception {
    Ddl ddl = getTestDdlForPrimaryKeyTest();
    JSONObject changeEvent = getTestChangeEvent("Users2");
    changeEvent.put("float64_field", "asdfasdf");
    JsonNode ce = parseChangeEvent(changeEvent.toString());
    Key key =
        ChangeEventSpannerConvertor.changeEventToPrimaryKey(
            "Users2", ddl, ce, /* convertNameToLowerCase= */ true);
    // Expect an exception since the event has invalid timestamp
  }

  @Test
  public void arrayConvertedAsNull() throws ChangeEventConvertorException {

    Ddl ddl =
        Ddl.builder()
            .createTable("Users")
            .column("first_name")
            .string()
            .max()
            .endColumn()
            .column("array_col")
            .type(Type.array(Type.int64()))
            .endColumn()
            .primaryKey()
            .asc("first_name")
            .end()
            .endTable()
            .build();

    JSONObject changeEvent = new JSONObject();
    changeEvent.put("first_name", "A");
    changeEvent.put("array_col", List.of(1L, 2L, 3L).toArray());
    changeEvent.put(Constants.EVENT_TABLE_NAME_KEY, "Users");
    JsonNode ce = parseChangeEvent(changeEvent.toString());
    Mutation mutation =
        ChangeEventSpannerConvertor.mutationFromEvent(
            ddl.table("Users"), ce, List.of("first_name", "array_col"), Set.of("first_name"));
    Map<String, Value> actual = mutation.asMap();
    Truth.assertThat(actual.get("first_name").getAsString()).isEqualTo("A");
    Truth.assertThat(actual.get("array_col")).isNull();
  }
}
