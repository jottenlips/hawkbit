/**
 * Copyright (c) 2021 Bosch.IO GmbH and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.hawkbit.repository.jpa.rsql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.MapJoin;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import cz.jirutka.rsql.parser.ast.AndNode;
import cz.jirutka.rsql.parser.ast.ComparisonNode;
import cz.jirutka.rsql.parser.ast.LogicalNode;
import cz.jirutka.rsql.parser.ast.Node;
import cz.jirutka.rsql.parser.ast.OrNode;
import cz.jirutka.rsql.parser.ast.RSQLVisitor;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.hawkbit.repository.FieldNameProvider;
import org.eclipse.hawkbit.repository.FieldValueConverter;
import org.eclipse.hawkbit.repository.exception.RSQLParameterSyntaxException;
import org.eclipse.hawkbit.repository.exception.RSQLParameterUnsupportedFieldException;
import org.eclipse.hawkbit.repository.rsql.VirtualPropertyReplacer;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

/**
 * An implementation of the {@link RSQLVisitor} to visit the parsed tokens and
 * build JPA where clauses.
 *
 * @param <A> the enum for providing the field name of the entity field to filter on.
 * @param <T> the entity type referenced by the root
 */
@Slf4j
public class JpaQueryRsqlVisitorG2<A extends Enum<A> & FieldNameProvider, T>
        extends AbstractFieldNameRSQLVisitor<A> implements RSQLVisitor<List<Predicate>, String> {

    public static final Character LIKE_WILDCARD = '*';
    private static final char ESCAPE_CHAR = '\\';
    private static final List<String> NO_JOINS_OPERATOR = List.of("!=", "=out=");
    private static final String ESCAPE_CHAR_WITH_ASTERISK = ESCAPE_CHAR +"*";

    private final Root<T> root;
    private final CriteriaQuery<?> query;
    private final CriteriaBuilder cb;
    private final Database database;
    private final VirtualPropertyReplacer virtualPropertyReplacer;
    private final boolean ensureIgnoreCase;

    private final SimpleTypeConverter simpleTypeConverter = new SimpleTypeConverter();

    private boolean inOr;
    private final Map<Class<?>, Path<Object>> javaTypeToPath = new HashMap<>();
    private boolean joinsNeeded;

    public JpaQueryRsqlVisitorG2(final Class<A> enumType,
            final Root<T> root, final CriteriaQuery<?> query, final CriteriaBuilder cb,
            final Database database, final VirtualPropertyReplacer virtualPropertyReplacer, final boolean ensureIgnoreCase) {
        super(enumType);
        this.root = root;
        this.cb = cb;
        this.query = query;
        this.virtualPropertyReplacer = virtualPropertyReplacer;
        this.database = database;
        this.ensureIgnoreCase = ensureIgnoreCase;
    }

    @Override
    public List<Predicate> visit(final AndNode node, final String param) {
        final List<Predicate> children = acceptChildren(node);
        if (children.isEmpty()) {
            return toSingleList(cb.conjunction());
        } else {
            return toSingleList(cb.and(children.toArray(new Predicate[0])));
        }
    }

    @Override
    public List<Predicate> visit(final OrNode node, final String param) {
        inOr = true;
        try {
            final List<Predicate> children = acceptChildren(node);
            if (children.isEmpty()) {
                return toSingleList(cb.conjunction());
            } else {
                return toSingleList(cb.or(children.toArray(new Predicate[0])));
            }
        } finally {
            inOr = false;
            javaTypeToPath.clear();
        }
    }

    @Override
    public List<Predicate> visit(final ComparisonNode node, final String param) {
        final A fieldName = getFieldEnumByName(node);
        final String finalProperty = getAndValidatePropertyFieldName(fieldName, node);

        final List<String> values = node.getArguments();
        final List<Object> transformedValues = new ArrayList<>();
        final Path<Object> fieldPath = getFieldPath(root, fieldName.getSubAttributes(finalProperty), fieldName.isMap());

        for (final String value : values) {
            transformedValues.add(convertValueIfNecessary(node, fieldName, fieldPath, value));
        }

        this.joinsNeeded = this.joinsNeeded || areJoinsNeeded(node);

        return mapToPredicate(node, fieldName, finalProperty, fieldPath, node.getArguments(), transformedValues);
    }

    private List<Predicate> mapToPredicate(final ComparisonNode node, final A enumField, final String finalProperty,
            final Path<Object> fieldPath,
            final List<String> values, final List<Object> transformedValues) {
        // if lookup is available, replace macros ...
        final String value = virtualPropertyReplacer == null ? values.get(0) : virtualPropertyReplacer.replace(values.get(0));

        final Predicate mapPredicate = mapToMapPredicate(node, enumField, fieldPath);
        final Predicate valuePredicate = addOperatorPredicate(node, enumField, finalProperty,
                getMapValueFieldPath(enumField, fieldPath), transformedValues, value);

        return toSingleList(mapPredicate != null ? cb.and(mapPredicate, valuePredicate) : valuePredicate);
    }

    @SuppressWarnings("unchecked")
    private Predicate mapToMapPredicate(final ComparisonNode node, final A enumField, final Path<Object> fieldPath) {
        if (!enumField.isMap()) {
            return null;
        }

        final String[] graph = enumField.getSubAttributes(node.getSelector());

        final String keyValue = graph[graph.length - 1];
        if (fieldPath instanceof MapJoin) {
            // Currently we support only string key. So below cast is safe.
            return equal((Expression<String>) (((MapJoin<?, ?, ?>) fieldPath).key()), keyValue);
        }

        final String keyFieldName = enumField.getSubEntityMapTuple().map(Entry::getKey)
                .orElseThrow(() -> new UnsupportedOperationException(
                        "For the fields, defined as Map, only Map java type or tuple in the form of " +
                                "SimpleImmutableEntry are allowed. Neither of those could be found!"));
        return equal(fieldPath.get(keyFieldName), keyValue);
    }

    private Predicate addOperatorPredicate(final ComparisonNode node, final A enumField, final String finalProperty,
            final Path<Object> fieldPath, final List<Object> transformedValues, final String value) {
        // only 'equal' and 'notEqual' can handle transformed value like enums.
        // The JPA API cannot handle object types for greaterThan etc. methods.
        final Object transformedValue = transformedValues.get(0);
        final String operator = node.getOperator().getSymbol();
        return switch (operator) {
            case "==" -> getEqualToPredicate(fieldPath, transformedValue);
            case "!=" -> getNotEqualToPredicate(enumField, finalProperty, fieldPath, transformedValue);
            case "=gt=" -> cb.greaterThan(pathOfString(fieldPath), value);
            case "=ge=" -> cb.greaterThanOrEqualTo(pathOfString(fieldPath), value);
            case "=lt=" -> cb.lessThan(pathOfString(fieldPath), value);
            case "=le=" -> cb.lessThanOrEqualTo(pathOfString(fieldPath), value);
            case "=in=" -> in(pathOfString(fieldPath), transformedValues);
            case "=out=" -> getOutPredicate(enumField, finalProperty, fieldPath, transformedValues);
            default -> throw new RSQLParameterSyntaxException(
                    "Operator symbol {" + operator + "} is either not supported or not implemented");
        };
    }

    private Predicate getEqualToPredicate(final Path<Object> fieldPath, final Object transformedValue) {
        if (transformedValue == null) {
            return cb.isNull(pathOfString(fieldPath));
        }

        if ((transformedValue instanceof String transformedValueStr) && !NumberUtils.isCreatable(transformedValueStr)) {
            if (ObjectUtils.isEmpty(transformedValue)) {
                return cb.or(cb.isNull(pathOfString(fieldPath)), cb.equal(pathOfString(fieldPath), ""));
            }

            if (isPattern(transformedValueStr)) { // a pattern, use like
                return like(pathOfString(fieldPath), toSQL(transformedValueStr));
            } else {
                return equal(pathOfString(fieldPath), transformedValueStr);
            }
        }

        return cb.equal(fieldPath, transformedValue);
    }

    private Predicate getNotEqualToPredicate(final A enumField, final String finalProperty,
            final Path<Object> fieldPath, final Object transformedValue) {
        if (transformedValue == null) {
            return cb.isNotNull(pathOfString(fieldPath));
        }

        if ((transformedValue instanceof String transformedValueStr) && !NumberUtils.isCreatable(transformedValueStr)) {
            if (ObjectUtils.isEmpty(transformedValue)) {
                return cb.and(cb.isNotNull(pathOfString(fieldPath)), cb.notEqual(pathOfString(fieldPath), ""));
            }

            final String[] fieldNames = enumField.getSubAttributes(finalProperty);

            if (isSimpleField(fieldNames, enumField.isMap())) {
                if (isPattern(transformedValueStr)) { // a pattern, use like
                    return cb.or(cb.isNull(pathOfString(fieldPath)), notLike(pathOfString(fieldPath), toSQL(transformedValueStr)));
                } else {
                    return toNullOrNotEqualPredicate(fieldPath, transformedValueStr);
                }
            }

            clearJoinsIfNotNeeded();

            return toNotExistsSubQueryPredicate(enumField, fieldNames, expressionToCompare ->
                            isPattern(transformedValueStr) ? // a pattern, use like
                                    like(expressionToCompare, toSQL(transformedValueStr)) :
                                    equal(expressionToCompare, transformedValueStr));
        }

        return toNullOrNotEqualPredicate(fieldPath, transformedValue);
    }

    private Predicate getOutPredicate(final A enumField, final String finalProperty, final Path<Object> fieldPath,
            final List<Object> transformedValues) {
        final String[] fieldNames = enumField.getSubAttributes(finalProperty);

        if (isSimpleField(fieldNames, enumField.isMap())) {
            final Path<String> pathOfString = pathOfString(fieldPath);
            return cb.or(cb.isNull(pathOfString), cb.not(in(pathOfString, transformedValues)));
        }

        clearJoinsIfNotNeeded();

        return toNotExistsSubQueryPredicate(enumField, fieldNames,
                expressionToCompare -> in(expressionToCompare, transformedValues));
    }

    private Path<Object> getFieldPath(
            final Root<?> root, final String[] split, final boolean isMapKeyField) {
        Path<Object> fieldPath = null;
        for (int i = 0, end = isMapKeyField ? split.length - 1 : split.length; i < end; i++) {
            final String fieldNameSplit = split[i];
            fieldPath = fieldPath == null ?
                    getPath(root, fieldNameSplit) : fieldPath.get(fieldNameSplit);
        }
        if (fieldPath == null) {
            throw new RSQLParameterUnsupportedFieldException("RSQL field path cannot be empty", null);
        }
        return fieldPath;
    }
    // if root.get creates a join we call join directly in order to specify LEFT JOIN type,
    // to include rows for missing in particular table / criteria (root.get creates INNER JOIN)
    // (see org.eclipse.persistence.internal.jpa.querydef.FromImpl implementation for more details)
    // otherwise delegate to root.get
    private Path<Object> getPath(final Root<?> root, final String fieldNameSplit) {
        // see org.eclipse.persistence.internal.jpa.querydef.FromImpl implementation for more details
        // when root.get creates a join
        final Attribute<?, ?> attribute = root.getModel().getAttribute(fieldNameSplit);
        if (!attribute.isCollection()) {
            // it is a SingularAttribute and not join if it is of basic persistent type
            if (((SingularAttribute<?, ?>) attribute).getType().getPersistenceType().equals(Type.PersistenceType.BASIC)) {
                return root.get(fieldNameSplit);
            }
        } // if a collection - it is a join
        if (inOr && root == this.root) { // try to reuse join of the same or level and no subquery
            final Class<?> objectClass = attribute.getJavaType();
            return javaTypeToPath.computeIfAbsent(objectClass, k -> root.join(fieldNameSplit, JoinType.LEFT));
        } else {
            return root.join(fieldNameSplit, JoinType.LEFT);
        }
    }

    private Object convertValueIfNecessary(
            final ComparisonNode node, final A fieldName, final Path<Object> fieldPath, final String value) {
        // in case the value of an RSQL query e.g. type==application is an
        // enum we need to handle it separately because JPA needs the
        // correct java-type to build an expression. So String and numeric
        // values JPA can do it by its own but not for classes like enums.
        // So we need to transform the given value string into the enum
        // class.
        final Class<?> javaType = fieldPath.getJavaType();
        if (javaType != null && javaType.isEnum()) {
            return transformEnumValue(node, javaType, value);
        }
        if (fieldName instanceof FieldValueConverter) {
            return convertFieldConverterValue(node, fieldName, value);
        }

        if (Boolean.TYPE.equals(javaType)) {
            return convertBooleanValue(node, javaType, value);
        }

        return value;
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object transformEnumValue(final ComparisonNode node, final Class<?> javaType, final String value) {
        final Class<? extends Enum> tmpEnumType = (Class<? extends Enum>) javaType;
        try {
            return Enum.valueOf(tmpEnumType, value.toUpperCase());
        } catch (final IllegalArgumentException e) {
            // we could not transform the given string value into the enum
            // type, so ignore it and return null and do not filter
            log.info("given value {} cannot be transformed into the correct enum type {}", value.toUpperCase(),
                    javaType);
            log.debug("value cannot be transformed to an enum", e);

            throw new RSQLParameterUnsupportedFieldException("field {" + node.getSelector()
                    + "} must be one of the following values {" + Arrays.stream(tmpEnumType.getEnumConstants())
                    .map(v -> v.name().toLowerCase()).toList()
                    + "}", e);
        }
    }
    private Object convertBooleanValue(final ComparisonNode node, final Class<?> javaType, final String value) {
        try {
            return simpleTypeConverter.convertIfNecessary(value, javaType);
        } catch (final TypeMismatchException e) {
            throw new RSQLParameterSyntaxException(
                    "The value of the given search parameter field {" + node.getSelector()
                            + "} is not well formed. Only a boolean (true or false) value will be expected {",
                    e);
        }
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Object convertFieldConverterValue(final ComparisonNode node, final A fieldName, final String value) {
        final Object convertedValue = ((FieldValueConverter) fieldName).convertValue(fieldName, value);
        if (convertedValue == null) {
            throw new RSQLParameterUnsupportedFieldException(
                    "field {" + node.getSelector() + "} must be one of the following values {"
                            + Arrays.toString(((FieldValueConverter) fieldName).possibleValues(fieldName)) + "}",
                    null);
        } else {
            return convertedValue;
        }
    }

    private Path<Object> getMapValueFieldPath(final A enumField, final Path<Object> fieldPath) {
        final String valueFieldNameFromSubEntity = enumField.getSubEntityMapTuple().map(Entry::getValue).orElse(null);

        if (!enumField.isMap() || valueFieldNameFromSubEntity == null) {
            return fieldPath;
        }
        return fieldPath.get(valueFieldNameFromSubEntity);
    }

    private void clearJoinsIfNotNeeded() {
        if (!joinsNeeded) {
            root.getJoins().clear();
        }
    }

    private Predicate toNullOrNotEqualPredicate(final Path<Object> fieldPath, final Object transformedValue) {
        return cb.or(
                cb.isNull(pathOfString(fieldPath)),
                transformedValue instanceof String transformedValueStr
                        ? notEqual(pathOfString(fieldPath), transformedValueStr)
                        : cb.notEqual(fieldPath, transformedValue));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Predicate toNotExistsSubQueryPredicate(final A enumField, final String[] fieldNames, final Function<Expression<String>, Predicate> subQueryPredicateProvider) {
        final Class<?> javaType = root.getJavaType();
        final Subquery<?> subquery = query.subquery(javaType);
        final Root subqueryRoot = subquery.from(javaType);
        final Predicate equalPredicate = cb.equal(root.get(enumField.identifierFieldName()),
                subqueryRoot.get(enumField.identifierFieldName()));
        final Expression<String> expressionToCompare = getExpressionToCompare(enumField,
                getFieldPath(subqueryRoot, fieldNames, enumField.isMap()));
        final Predicate subQueryPredicate = subQueryPredicateProvider.apply(expressionToCompare);
        subquery.select(subqueryRoot).where(cb.and(equalPredicate, subQueryPredicate));
        return cb.not(cb.exists(subquery));
    }
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private Expression<String> getExpressionToCompare(final A enumField, final Path fieldPath) {
        if (!enumField.isMap()) {
            return pathOfString(fieldPath);
        }
        if (fieldPath instanceof MapJoin) {
            // Currently we support only string key. So below cast is safe.
            return (Expression<String>) (((MapJoin<?, ?, ?>) pathOfString(fieldPath)).value());
        }
        final String valueFieldName = enumField.getSubEntityMapTuple().map(Entry::getValue)
                .orElseThrow(() -> new UnsupportedOperationException(
                        "For the fields, defined as Map, only Map java type or tuple in the form of SimpleImmutableEntry are allowed. Neither of those could be found!"));
        return pathOfString(fieldPath).get(valueFieldName);
    }

    private String toSQL(final String transformedValue) {
        final String escaped;
        if (database == Database.SQL_SERVER) {
            escaped = transformedValue.replace("%", "[%]").replace("_", "[_]");
        } else {
            escaped = transformedValue.replace("%", ESCAPE_CHAR + "%").replace("_", ESCAPE_CHAR + "_");
        }
        return replaceIfRequired(escaped);
    }
    private String replaceIfRequired(final String escapedValue) {
        final String finalizedValue;
        if (escapedValue.contains(ESCAPE_CHAR_WITH_ASTERISK)) {
            finalizedValue = escapedValue.replace(ESCAPE_CHAR_WITH_ASTERISK, "$").replace(LIKE_WILDCARD, '%')
                    .replace("$", ESCAPE_CHAR_WITH_ASTERISK);
        } else {
            finalizedValue = escapedValue.replace(LIKE_WILDCARD, '%');
        }
        return finalizedValue;
    }

    private List<Predicate> acceptChildren(final LogicalNode node) {
        final List<Predicate> children = new ArrayList<>();
        for (final Node child : node.getChildren()) {
            final List<Predicate> accept = child.accept(this);
            if (!CollectionUtils.isEmpty(accept)) {
                children.addAll(accept);
            } else {
                log.debug("visit logical node children but could not parse it, ignoring {}", child);
            }
        }
        return children;
    }

    private Predicate equal(final Expression<String> expressionToCompare, final String sqlValue) {
        return cb.equal(caseWise(cb, expressionToCompare), caseWise(sqlValue));
    }
    private Predicate notEqual(final Expression<String> expressionToCompare, String transformedValueStr) {
        return cb.notEqual(caseWise(cb, expressionToCompare), caseWise(transformedValueStr));
    }
    private Predicate like(final Expression<String> expressionToCompare, final String sqlValue) {
        return cb.like(caseWise(cb, expressionToCompare), caseWise(sqlValue), ESCAPE_CHAR);
    }
    private Predicate notLike(final Expression<String> expressionToCompare, final String sqlValue) {
        return cb.notLike(caseWise(cb, expressionToCompare), caseWise(sqlValue), ESCAPE_CHAR);
    }
    private Predicate in(final Expression<String> expressionToCompare, final List<Object> transformedValues) {
        final List<String> inParams = transformedValues.stream().filter(String.class::isInstance)
                .map(String.class::cast).map(this::caseWise).collect(Collectors.toList());
        return inParams.isEmpty() ? expressionToCompare.in(transformedValues) : caseWise(cb, expressionToCompare).in(inParams);
    }

    private Expression<String> caseWise(final CriteriaBuilder cb, final Expression<String> expression) {
        return ensureIgnoreCase ? cb.upper(expression) : expression;
    }
    private String caseWise(final String str) {
        return ensureIgnoreCase ? str.toUpperCase() : str;
    }

    private static boolean isSimpleField(final String[] split, final boolean isMapKeyField) {
        return split.length == 1 || (split.length == 2 && isMapKeyField);
    }

    private static List<Predicate> toSingleList(final Predicate predicate) {
        return Collections.singletonList(predicate);
    }

    @SuppressWarnings("unchecked")
    private static <Y> Path<Y> pathOfString(final Path<?> path) {
        return (Path<Y>) path;
    }

    private static boolean isPattern(final String transformedValue) {
        if (transformedValue.contains(ESCAPE_CHAR_WITH_ASTERISK)) {
            return transformedValue.replace(ESCAPE_CHAR_WITH_ASTERISK, "$").indexOf(LIKE_WILDCARD) != -1;
        } else {
            return transformedValue.indexOf(LIKE_WILDCARD) != -1;
        }
    }

    private static boolean areJoinsNeeded(final ComparisonNode node) {
        return !NO_JOINS_OPERATOR.contains(node.getOperator().getSymbol());
    }
}