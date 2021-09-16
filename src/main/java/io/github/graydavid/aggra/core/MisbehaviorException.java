/*
 * Copyright 2021 David Gray
 * 
 * SPDX-License-Identifier: Apache-2.0
 */

package io.github.graydavid.aggra.core;

/**
 * Indicates that some user-provided implementation of a Aggra framework concept is not meeting its contract. The
 * presence of this exception should be treated seriously, since it means Aggra can't guarantee its contract while the
 * faulty implementations exist.
 */
public class MisbehaviorException extends RuntimeException {
    private static final long serialVersionUID = 1;

    public MisbehaviorException(String message) {
        super(message);
    }
}
