/*
 * (C) Copyright 2021 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *      Nelson Silva
 *      Tiry
 */
package org.nuxeo.statistics.repository.impl;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.metrics.Sum;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.statistics.repository.BaseESStatisticsComputer;

import io.dropwizard.metrics5.MetricName;

public class ESRepositoryStatisticsComputer extends BaseESStatisticsComputer {

	private static final Log log = LogFactory.getLog(ESRepositoryStatisticsComputer.class);

	public static final List<String> INTERNAL_TYPES = Arrays.asList("MailMessage", "CommentRelation", "Tagging",
			"UserRegistrationContainer");

	public static final List<String> EXCLUDED_TYPES = Arrays.asList("Root", "AdministrativeStatus", "MailMessage",
			"CommentRelation", "Tagging", "UserRegistrationContainer", "AdministrativeStatusContainer", "TemplateRoot",
			"TaskRoot", "ManagementRoot", "DocumentRouteModelsRoot", "WorkspaceRoot", "SectionRoot");

	private static final int MAX_DOCUMENT_TYPES = 10000;

	protected final List<String> repositoryNames;

	public ESRepositoryStatisticsComputer() {
		RepositoryManager rm = Framework.getService(RepositoryManager.class);
		repositoryNames = rm.getRepositoryNames();
	}

	@Override
	public Map<MetricName, Long> get() {

		Map<MetricName, Long> metrics = new HashMap<>();

		for (String repositoryName : repositoryNames) {

			metrics.putAll(getCountsPerDocTypes(repositoryName));

			Map<MetricName, Long> size = getBlobSize(repositoryName);
			metrics.putAll(size);
		}
		return metrics;
	}

	protected SearchRequest searchRequest(String repositoryName) {
		return new SearchRequest(getESIndexName(repositoryName)).searchType(SearchType.DFS_QUERY_THEN_FETCH);
	}

	protected Map<MetricName, Long> getCountsPerDocTypes(String repositoryName) {

		Map<MetricName, Long> ret = new LinkedHashMap<>();
		SearchRequest searchRequest = searchRequest(repositoryName);
		BoolQueryBuilder boolq = QueryBuilders.boolQuery();
		EXCLUDED_TYPES.forEach(lang -> {
			boolq.mustNot(QueryBuilders.termQuery("ecm:primaryType", lang));
		});
		SearchSourceBuilder sourceBuilder = new SearchSourceBuilder().size(0)
				.query(boolq.mustNot(QueryBuilders.termQuery("ecm:isProxy", "true"))).aggregation(
						AggregationBuilders.terms("primaryType").size(MAX_DOCUMENT_TYPES).field("ecm:primaryType"));
		searchRequest.source(sourceBuilder);
		try {
			SearchResponse response = getClient().search(searchRequest);

			Terms terms = response.getAggregations().get("primaryType");
			for (Terms.Bucket term : terms.getBuckets()) {
				MetricName mn = mkMetricName("repository", "documents").tagged("repository", repositoryName)
						.tagged("doctype", term.getKeyAsString());
				ret.put(mn, term.getDocCount());
			}
		} catch (Exception e) {
			log.error("Failed to get Type Cardinality", e);
		}
		return ret;
	}

	protected Map<MetricName, Long> getBlobSize(String repositoryName) {

		Map<MetricName, Long> ret = new LinkedHashMap<>();
		SearchRequest searchRequest = searchRequest(repositoryName);
		BoolQueryBuilder boolq = QueryBuilders.boolQuery();
		EXCLUDED_TYPES.forEach(lang -> {
			boolq.mustNot(QueryBuilders.termQuery("ecm:primaryType", lang));
		});

		SearchSourceBuilder sourceBuilder = new SearchSourceBuilder().size(0)
				.query(boolq.mustNot(QueryBuilders.termQuery("ecm:isProxy", "true")))
				.aggregation(AggregationBuilders.sum("blobSize").field("file:content.length"));
		searchRequest.source(sourceBuilder);
		try {
			SearchResponse response = getClient().search(searchRequest);
			Sum sum = response.getAggregations().get("blobSize");
			MetricName mn = mkMetricName("repository", "blobs", "mainBlobs").tagged("repository", repositoryName);
			ret.put(mn, (long) sum.getValue());
		} catch (Exception e) {
			log.error("Failed to get Type Cardinality", e);
		}
		return ret;
	}
}
