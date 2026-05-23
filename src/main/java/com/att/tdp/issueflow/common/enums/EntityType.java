package com.att.tdp.issueflow.common.enums;

/**
 * Identifies which kind of entity an audit-log row refers to.
 * Listed in spec 06.
 */
public enum EntityType {
    TICKET,
    PROJECT,
    USER,
    COMMENT,
    ATTACHMENT,
    DEPENDENCY
}
