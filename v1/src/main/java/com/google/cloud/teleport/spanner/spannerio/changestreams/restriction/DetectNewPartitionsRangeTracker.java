/*
 * Copyright (C) 2025 Google LLC
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
package com.google.cloud.teleport.spanner.spannerio.changestreams.restriction;

import com.google.cloud.Timestamp;

/**
 * This restriction tracker delegates most of its behavior to an internal {@link
 * com.google.cloud.teleport.spanner.spannerio.changestreams.restriction.TimestampRangeTracker}. It
 * has a different logic for tryClaim method. It ignores claims for the same timestamp multiple
 * times.
 */
@SuppressWarnings({
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class DetectNewPartitionsRangeTracker extends TimestampRangeTracker {

  public DetectNewPartitionsRangeTracker(
      com.google.cloud.teleport.spanner.spannerio.changestreams.restriction.TimestampRange range) {
    super(range);
  }

  /**
   * Attempts to claim the given position.
   *
   * <p>Must be equal or larger than the last successfully claimed position.
   *
   * @return {@code true} if the position was successfully claimed, {@code false} if it is outside
   *     the current {@link TimestampRange} of this tracker (in that case this operation is a
   *     no-op).
   */
  @Override
  public boolean tryClaim(Timestamp position) {
    if (position.equals(lastAttemptedPosition)) {
      return true;
    }

    return super.tryClaim(position);
  }
}
