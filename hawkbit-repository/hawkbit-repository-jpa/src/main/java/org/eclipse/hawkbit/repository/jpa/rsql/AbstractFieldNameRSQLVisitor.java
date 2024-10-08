/**
 * Copyright (c) 2020 devolo AG and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hawkbit.repository.jpa.rsql;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotNull;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.hawkbit.repository.FieldNameProvider;
import org.eclipse.hawkbit.repository.exception.RSQLParameterUnsupportedFieldException;

import cz.jirutka.rsql.parser.ast.ComparisonNode;

@Slf4j
public abstract class AbstractFieldNameRSQLVisitor<A extends Enum<A> & FieldNameProvider> {

    private final Class<A> fieldNameProvider;

    protected AbstractFieldNameRSQLVisitor(final Class<A> fieldNameProvider) {
        this.fieldNameProvider = fieldNameProvider;
    }

    protected A getFieldEnumByName(final ComparisonNode node) {
        final String[] graph = node.getSelector().split(FieldNameProvider.SUB_ATTRIBUTE_SPLIT_REGEX);
        final String enumName = graph.length == 0 ? node.getSelector() : graph[0];
        log.debug("get field identifier by name {} of enum type {}", enumName, fieldNameProvider);
        try {
            return Enum.valueOf(fieldNameProvider, enumName.toUpperCase());
        } catch (final IllegalArgumentException e) {
            throw createRSQLParameterUnsupportedException(node, e);
        }
    }

    protected String getAndValidatePropertyFieldName(final A propertyEnum, final ComparisonNode node) {
        final String[] subAttributes = propertyEnum.getSubAttributes(node.getSelector());
        validateMapParameter(propertyEnum, node, subAttributes);

        // sub entity need minimum 1 dot
        if (!propertyEnum.getSubEntityAttributes().isEmpty() && subAttributes.length < 2) {
            throw createRSQLParameterUnsupportedException(node, null);
        }

        final StringBuilder fieldNameBuilder = new StringBuilder(propertyEnum.getFieldName());
        for (int i = 1; i < subAttributes.length; i++) {
            final String propertyField = getFormattedSubEntityAttribute(propertyEnum ,subAttributes[i]);
            fieldNameBuilder.append(FieldNameProvider.SUB_ATTRIBUTE_SEPARATOR).append(propertyField);

            // the key of map is not in the graph
            if (propertyEnum.isMap() && subAttributes.length == (i + 1)) {
                continue;
            }

            if (!propertyEnum.containsSubEntityAttribute(propertyField)) {
                throw createRSQLParameterUnsupportedException(node, null);
            }
        }

        return fieldNameBuilder.toString();
    }

    private void validateMapParameter(final A propertyEnum, final ComparisonNode node, final String[] subAttributes) {
        if (!propertyEnum.isMap()) {
            return;
        }

        if (!propertyEnum.getSubEntityAttributes().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Currently sub-entity attributes for maps are not supported, alternatively you could use the key/value tuple, defined by SimpleImmutableEntry class");
        }

        // enum.key
        if (subAttributes.length != 2) {
            throw new RSQLParameterUnsupportedFieldException("The syntax of the given map search parameter field {" +
                    node.getSelector() + "} is wrong. Syntax is: <enum name>.<key name>");
        }
    }

    /**
     * @param node current processing node
     * @param rootException in case there is a cause otherwise {@code null}
     * @return Exception with prepared message extracted from the comparison node.
     */
    protected RSQLParameterUnsupportedFieldException createRSQLParameterUnsupportedException(
            @NotNull final ComparisonNode node,
            final Exception rootException) {
        return new RSQLParameterUnsupportedFieldException(String.format(
                "The given search parameter field {%s} does not exist, must be one of the following fields %s",
                node.getSelector(), getExpectedFieldList()), rootException);
    }

    private String getFormattedSubEntityAttribute(final A propertyEnum, final String propertyField) {
        return propertyEnum.getSubEntityAttributes().stream()
                .filter(attr -> attr.equalsIgnoreCase(propertyField))
                .findFirst().orElse(propertyField);
    }

    private List<String> getExpectedFieldList() {
        final List<String> expectedFieldList = Arrays.stream(fieldNameProvider.getEnumConstants())
                .filter(enumField -> enumField.getSubEntityAttributes().isEmpty()).map(enumField -> {
                    final String enumFieldName = enumField.name().toLowerCase();
                    if (enumField.isMap()) {
                        return enumFieldName + FieldNameProvider.SUB_ATTRIBUTE_SEPARATOR + "keyName";
                    } else {
                        return enumFieldName;
                    }
                }).collect(Collectors.toList());

        final List<String> expectedSubFieldList = Arrays.stream(fieldNameProvider.getEnumConstants())
                .filter(enumField -> !enumField.getSubEntityAttributes().isEmpty()).flatMap(enumField -> {
                    final List<String> subEntity = enumField
                            .getSubEntityAttributes().stream().map(fieldName -> enumField.name().toLowerCase()
                                    + FieldNameProvider.SUB_ATTRIBUTE_SEPARATOR + fieldName)
                            .toList();

                    return subEntity.stream();
                }).toList();
        expectedFieldList.addAll(expectedSubFieldList);
        return expectedFieldList;
    }
}