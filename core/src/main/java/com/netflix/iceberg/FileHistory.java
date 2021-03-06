/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.netflix.iceberg.exceptions.RuntimeIOException;
import com.netflix.iceberg.expressions.Literal;
import com.netflix.iceberg.io.CloseableIterable;
import com.netflix.iceberg.types.Types;
import com.netflix.iceberg.util.CharSequenceWrapper;
import java.io.IOException;
import java.util.List;
import java.util.Set;

public class FileHistory {
  private static final List<String> HISTORY_COLUMNS = ImmutableList.of("file_path");

  private FileHistory() {
  }

  public static Builder table(Table table) {
    return new Builder(table);
  }

  public static class Builder {
    private final Table table;
    private final Set<CharSequenceWrapper> locations = Sets.newHashSet();
    private Long startTime = null;
    private Long endTime = null;

    public Builder(Table table) {
      this.table = table;
    }

    public Builder location(String location) {
      locations.add(CharSequenceWrapper.wrap(location));
      return this;
    }

    public Builder after(String timestamp) {
      Literal<Long> tsLiteral = Literal.of(timestamp).to(Types.TimestampType.withoutZone());
      this.startTime = tsLiteral.value() / 1000;
      return this;
    }

    public Builder after(long timestampMillis) {
      this.startTime = timestampMillis;
      return this;
    }

    public Builder before(String timestamp) {
      Literal<Long> tsLiteral = Literal.of(timestamp).to(Types.TimestampType.withoutZone());
      this.endTime = tsLiteral.value() / 1000;
      return this;
    }

    public Builder before(long timestampMillis) {
      this.endTime = timestampMillis;
      return this;
    }

    @SuppressWarnings("unchecked")
    public Iterable<ManifestEntry> build() {
      Iterable<Snapshot> snapshots = table.snapshots();

      if (startTime != null) {
        snapshots = Iterables.filter(snapshots, snap -> snap.timestampMillis() >= startTime);
      }

      if (endTime != null) {
        snapshots = Iterables.filter(snapshots, snap -> snap.timestampMillis() <= endTime);
      }

      // a manifest group will only read each manifest once
      ManifestGroup manifests = new ManifestGroup(((HasTableOperations) table).operations(),
          Iterables.concat(Iterables.transform(snapshots, Snapshot::manifests)));

      List<ManifestEntry> results = Lists.newArrayList();
      try (CloseableIterable<ManifestEntry> entries = manifests.select(HISTORY_COLUMNS).entries()) {
        // TODO: replace this with an IN predicate
        CharSequenceWrapper locationWrapper = CharSequenceWrapper.wrap(null);
        for (ManifestEntry entry : entries) {
          if (entry != null && locations.contains(locationWrapper.set(entry.file().path()))) {
            results.add(entry.copy());
          }
        }
      } catch (IOException e) {
        throw new RuntimeIOException(e);
      }

      return results;
    }
  }
}
