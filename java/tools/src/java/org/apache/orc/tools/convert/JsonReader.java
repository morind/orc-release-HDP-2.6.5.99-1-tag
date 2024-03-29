/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.orc.tools.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonStreamParser;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DecimalColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.ListColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.StructColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.TimestampColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.orc.RecordReader;
import org.apache.orc.TypeDescription;
import org.threeten.bp.LocalDateTime;
import org.threeten.bp.ZonedDateTime;
import org.threeten.bp.ZoneId;
import org.threeten.bp.format.DateTimeFormatter;
import org.threeten.bp.temporal.TemporalAccessor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class JsonReader implements RecordReader {
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
    "yyyy[[-][/]]MM[[-][/]]dd[['T'][ ]]HH:mm:ss[ ][XXX][X]");

  private final TypeDescription schema;
  private final JsonStreamParser parser;
  private final JsonConverter[] converters;
  private final long totalSize;
  private final FSDataInputStream rawStream;
  private long rowNumber = 0;

  interface JsonConverter {
    void convert(JsonElement value, ColumnVector vect, int row);
  }

  static class BooleanColumnConverter implements JsonConverter {
    public void convert(JsonElement value, ColumnVector vect, int row) {
      if (value == null || value.isJsonNull()) {
        vect.noNulls = false;
        vect.isNull[row] = true;
      } else {
        LongColumnVector vector = (LongColumnVector) vect;
        vector.vector[row] = value.getAsBoolean() ? 1 : 0;
      }
    }
  }

  static class LongColumnConverter implements JsonConverter {
    public void convert(JsonElement value, ColumnVector vect, int row) {
      if (value == null || value.isJsonNull()) {
        vect.noNulls = false;
        vect.isNull[row] = true;
      } else {
        LongColumnVector vector = (LongColumnVector) vect;
        vector.vector[row] = value.getAsLong();
      }
    }
  }

  static class DoubleColumnConverter implements JsonConverter {
    public void convert(JsonElement value, ColumnVector vect, int row) {
      if (value == null || value.isJsonNull()) {
        vect.noNulls = false;
        vect.isNull[row] = true;
      } else {
        DoubleColumnVector vector = (DoubleColumnVector) vect;
        vector.vector[row] = value.getAsDouble();
      }
    }
  }

  static class StringColumnConverter implements JsonConverter {
    public void convert(JsonElement value, ColumnVector vect, int row) {
      if (value == null || value.isJsonNull()) {
        vect.noNulls = false;
        vect.isNull[row] = true;
      } else {
        BytesColumnVector vector = (BytesColumnVector) vect;
        byte[] bytes = value.getAsString().getBytes(StandardCharsets.UTF_8);
        vector.setRef(row, bytes, 0, bytes.length);
      }
    }
  }

  static class BinaryColumnConverter implements JsonConverter {
    public void convert(JsonElement value, ColumnVector vect, int row) {
      if (value == null || value.isJsonNull()) {
        vect.noNulls = false;
        vect.isNull[row] = true;
      } else {
        BytesColumnVector vector = (BytesColumnVector) vect;
        String binStr = value.getAsString();
        byte[] bytes = new byte[binStr.length()/2];
        for(int i=0; i < bytes.length; ++i) {
          bytes[i] = (byte) Integer.parseInt(binStr.substring(i*2, i*2+2), 16);
        }
        vector.setRef(row, bytes, 0, bytes.length);
      }
    }
  }

  static class TimestampColumnConverter implements JsonConverter {
    public void convert(JsonElement value, ColumnVector vect, int row) {
      if (value == null || value.isJsonNull()) {
        vect.noNulls = false;
        vect.isNull[row] = true;
      } else {
        TimestampColumnVector vector = (TimestampColumnVector) vect;
        TemporalAccessor temporalAccessor = DATE_TIME_FORMATTER.parseBest(value.getAsString(),
          ZonedDateTime.FROM, LocalDateTime.FROM);
        if (temporalAccessor instanceof ZonedDateTime) {
          vector.set(row, new Timestamp(((ZonedDateTime) temporalAccessor).toEpochSecond() * 1000L));
        } else if (temporalAccessor instanceof LocalDateTime) {
          vector.set(row, new Timestamp(((LocalDateTime) temporalAccessor).atZone(ZoneId.systemDefault()).toEpochSecond() * 1000L));
        } else {
          vect.noNulls = false;
          vect.isNull[row] = true;
        }
      }
    }
  }

  static class DecimalColumnConverter implements JsonConverter {
    public void convert(JsonElement value, ColumnVector vect, int row) {
      if (value == null || value.isJsonNull()) {
        vect.noNulls = false;
        vect.isNull[row] = true;
      } else {
        DecimalColumnVector vector = (DecimalColumnVector) vect;
        vector.vector[row].set(HiveDecimal.create(value.getAsString()));
      }
    }
  }

  static class StructColumnConverter implements JsonConverter {
    private JsonConverter[] childrenConverters;
    private List<String> fieldNames;

    public StructColumnConverter(TypeDescription schema) {
      List<TypeDescription> kids = schema.getChildren();
      childrenConverters = new JsonConverter[kids.size()];
      for(int c=0; c < childrenConverters.length; ++c) {
        childrenConverters[c] = createConverter(kids.get(c));
      }
      fieldNames = schema.getFieldNames();
    }

    public void convert(JsonElement value, ColumnVector vect, int row) {
      if (value == null || value.isJsonNull()) {
        vect.noNulls = false;
        vect.isNull[row] = true;
      } else {
        StructColumnVector vector = (StructColumnVector) vect;
        JsonObject obj = value.getAsJsonObject();
        for(int c=0; c < childrenConverters.length; ++c) {
          JsonElement elem = obj.get(fieldNames.get(c));
          childrenConverters[c].convert(elem, vector.fields[c], row);
        }
      }
    }
  }

  static class ListColumnConverter implements JsonConverter {
    private JsonConverter childrenConverter;

    public ListColumnConverter(TypeDescription schema) {
      childrenConverter = createConverter(schema.getChildren().get(0));
    }

    public void convert(JsonElement value, ColumnVector vect, int row) {
      if (value == null || value.isJsonNull()) {
        vect.noNulls = false;
        vect.isNull[row] = true;
      } else {
        ListColumnVector vector = (ListColumnVector) vect;
        JsonArray obj = value.getAsJsonArray();
        vector.lengths[row] = obj.size();
        vector.offsets[row] = vector.childCount;
        vector.childCount += vector.lengths[row];
        vector.child.ensureSize(vector.childCount, true);
        for(int c=0; c < obj.size(); ++c) {
          childrenConverter.convert(obj.get(c), vector.child,
              (int) vector.offsets[row] + c);
        }
      }
    }
  }

  static JsonConverter createConverter(TypeDescription schema) {
    switch (schema.getCategory()) {
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
        return new LongColumnConverter();
      case FLOAT:
      case DOUBLE:
        return new DoubleColumnConverter();
      case CHAR:
      case VARCHAR:
      case STRING:
        return new StringColumnConverter();
      case DECIMAL:
        return new DecimalColumnConverter();
      case TIMESTAMP:
        return new TimestampColumnConverter();
      case BINARY:
        return new BinaryColumnConverter();
      case BOOLEAN:
        return new BooleanColumnConverter();
      case STRUCT:
        return new StructColumnConverter(schema);
      case LIST:
        return new ListColumnConverter(schema);
      default:
        throw new IllegalArgumentException("Unhandled type " + schema);
    }
  }

  public JsonReader(Path path,
                    TypeDescription schema,
                    Configuration conf) throws IOException {
    this.schema = schema;
    FileSystem fs = path.getFileSystem(conf);
    totalSize = fs.getFileStatus(path).getLen();
    rawStream = fs.open(path);
    String name = path.getName();
    int lastDot = name.lastIndexOf(".");
    InputStream input = rawStream;
    if (lastDot >= 0) {
      if (".gz".equals(name.substring(lastDot))) {
        input = new GZIPInputStream(rawStream);
      }
    }
    parser = new JsonStreamParser(new InputStreamReader(input,
        StandardCharsets.UTF_8));
    if (schema.getCategory() != TypeDescription.Category.STRUCT) {
      throw new IllegalArgumentException("Root must be struct - " + schema);
    }
    List<TypeDescription> fieldTypes = schema.getChildren();
    converters = new JsonConverter[fieldTypes.size()];
    for(int c = 0; c < converters.length; ++c) {
      converters[c] = createConverter(fieldTypes.get(c));
    }
  }

  public boolean nextBatch(VectorizedRowBatch batch) throws IOException {
    batch.reset();
    int maxSize = batch.getMaxSize();
    List<String> fieldNames = schema.getFieldNames();
    while (parser.hasNext() && batch.size < maxSize) {
      JsonObject elem = parser.next().getAsJsonObject();
      for(int c=0; c < converters.length; ++c) {
        // look up each field to see if it is in the input, otherwise
        // set it to null.
        JsonElement field = elem.get(fieldNames.get(c));
        if (field == null) {
          batch.cols[c].noNulls = false;
          batch.cols[c].isNull[batch.size] = true;
        } else {
          converters[c].convert(field, batch.cols[c], batch.size);
        }
      }
      batch.size++;
    }
    rowNumber += batch.size;
    return batch.size != 0;
  }

  @Override
  public long getRowNumber() throws IOException {
    return rowNumber;
  }

  @Override
  public float getProgress() throws IOException {
    long pos = rawStream.getPos();
    return totalSize != 0 && pos < totalSize ? (float) pos / totalSize : 1;
  }

  public void close() throws IOException {
    rawStream.close();
  }

  @Override
  public void seekToRow(long rowCount) throws IOException {
    throw new UnsupportedOperationException("Seek is not supported by JsonReader");
  }
}
