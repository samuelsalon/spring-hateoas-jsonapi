/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.toedter.spring.hateoas.jsonapi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.With;
import org.springframework.hateoas.*;
import org.springframework.lang.Nullable;

import java.lang.reflect.Field;
import java.util.*;

import static com.toedter.spring.hateoas.jsonapi.ReflectionUtils.getAllDeclaredFields;

@Getter(onMethod_ = {@JsonProperty})
@With(AccessLevel.PACKAGE)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
class JsonApiData {
    String id;
    String type;
    Map<String, Object> attributes;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    Object relationships;
    Links links;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    Map<String, Object> meta;

    @JsonCreator
    public JsonApiData(
            @JsonProperty("id") @Nullable String id,
            @JsonProperty("type") @Nullable String type,
            @JsonProperty("attributes") @Nullable Map<String, Object> attributes,
            @JsonProperty("relationships") @Nullable Object relationships,
            @JsonProperty("links") @Nullable Links links,
            @JsonProperty("meta") @Nullable Map<String, Object> meta
    ) {
        this.id = id;
        this.type = type;
        this.attributes = attributes;
        this.relationships = relationships;
        this.links = links;
        this.meta = meta;
    }

    private static class JsonApiDataWithoutSerializedAttributes extends JsonApiData {
        JsonApiDataWithoutSerializedAttributes(JsonApiData jsonApiData) {
            super(jsonApiData.id, jsonApiData.type, null,
                    jsonApiData.relationships, jsonApiData.links, jsonApiData.meta);
        }

        @Override
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public @Nullable
        Map<String, Object> getAttributes() {
            return null;
        }
    }

    public static List<JsonApiData> extractCollectionContent(
            CollectionModel<?> collectionModel,
            ObjectMapper objectMapper,
            JsonApiConfiguration jsonApiConfiguration,
            @Nullable HashMap<String, Collection<String>> sparseFieldsets,
            boolean eliminateDuplicates) {

        if (eliminateDuplicates) {
            HashMap<String, JsonApiData> values = new HashMap<>();
            for (Object entity : collectionModel.getContent()) {
                Optional<JsonApiData> jsonApiData =
                        extractContent(entity, false, objectMapper, jsonApiConfiguration, sparseFieldsets);
                jsonApiData.ifPresent(apiData -> values.put(apiData.getId() + "." + apiData.getType(), apiData));
            }
            return new ArrayList<>(values.values());
        } else {
            List<JsonApiData> dataList = new ArrayList<>();
            for (Object entity : collectionModel.getContent()) {
                Optional<JsonApiData> jsonApiData =
                        extractContent(entity, false, objectMapper, jsonApiConfiguration, sparseFieldsets);
                jsonApiData.ifPresent(dataList::add);
            }
            return dataList;
        }
    }

    public static Optional<JsonApiData> extractContent(
            @Nullable Object content,
            boolean isSingleEntity,
            ObjectMapper objectMapper,
            JsonApiConfiguration jsonApiConfiguration,
            @Nullable HashMap<String, Collection<String>> sparseFieldsets) {

        Links links = null;
        Map<String, JsonApiRelationship> relationships = null;
        Map<String, Object> metaData = null;

        if (content instanceof RepresentationModel<?>) {
            links = ((RepresentationModel<?>) content).getLinks();
        }

        if (content instanceof JsonApiModel) {
            JsonApiModel jsonApiRepresentationModel = (JsonApiModel) content;
            relationships = jsonApiRepresentationModel.getRelationships();
            sparseFieldsets = jsonApiRepresentationModel.getSparseFieldsets();
            metaData = jsonApiRepresentationModel.getMetaData();
            content = jsonApiRepresentationModel.getContent();
        }

        if (content instanceof EntityModel) {
            content = ((EntityModel<?>) content).getContent();
        }

        if (content == null) {
            // will lead to "data":null, which is compliant with the JSON:API spec
            return Optional.empty();
        }

        // when running with code coverage in IDEs,
        // some additional fields might be introduced.
        // Those should be ignored.
        final Field[] fields = getAllDeclaredFields(content.getClass());
        boolean validFieldFound = false;
        for(Field field: fields) {
            if(!"$jacocoData".equals(field.getName()) && !"__$lineHits$__".equals(field.getName())
                    && (!(content instanceof RepresentationModel && "links".equals(field.getName())))) {
                validFieldFound = true;
                break;
            }
        }
        if (!validFieldFound) {
            return Optional.empty();
        }

        JsonApiResourceIdentifier.ResourceField idField;
        idField = JsonApiResourceIdentifier.getId(content, jsonApiConfiguration);

        if (isSingleEntity || links != null && links.isEmpty()) {
            links = null;
        }
        JsonApiResourceIdentifier.ResourceField typeField = JsonApiResourceIdentifier.getType(content, jsonApiConfiguration);

        Map<String, Object> attributeMap = objectMapper.convertValue(content, Map.class);

        attributeMap.remove("links");
        attributeMap.remove(idField.name);

        // fix #164
        if(!content.getClass().isAnnotationPresent(JsonApiTypeForClass.class)) {
            attributeMap.remove(typeField.name);
        }

        Links finalLinks = links;
        String finalId = idField.value;
        String finalType = typeField.value;
        Map<String, JsonApiRelationship> finalRelationships = relationships;
        Map<String, Object> finalMetaData = metaData;

        // apply sparse fieldsets
        if (sparseFieldsets != null) {
            Collection<String> attributes = sparseFieldsets.get(finalType);
            if (attributes != null) {
                Set<String> keys = new HashSet<>(attributeMap.keySet());
                for (String key : keys) {
                    if (!attributes.contains(key)) {
                        attributeMap.remove(key);
                    }
                }
            }
        }

        JsonApiData jsonApiData = new JsonApiData(
                finalId, finalType, attributeMap, finalRelationships, finalLinks, finalMetaData);

        if(attributeMap.size() > 0 || jsonApiConfiguration.isEmptyAttributesObjectSerialized()) {
            return Optional.of(content)
                    .filter(it -> !RESOURCE_TYPES.contains(it.getClass()))
                    .map(it -> jsonApiData);
        } else {
            return Optional.of(content)
                    .filter(it -> !RESOURCE_TYPES.contains(it.getClass()))
                    .map(it -> new JsonApiDataWithoutSerializedAttributes(jsonApiData));
        }
    }

    static final HashSet<Class<?>> RESOURCE_TYPES = new HashSet<>(
            Arrays.asList(RepresentationModel.class, EntityModel.class, CollectionModel.class, PagedModel.class));
}
