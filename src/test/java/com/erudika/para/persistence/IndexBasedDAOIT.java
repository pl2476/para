/*
 * Copyright 2014 Erudika.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.erudika.para.persistence;

import com.erudika.para.search.ElasticSearch;
import com.erudika.para.search.ElasticSearchUtils;
import com.erudika.para.search.Search;
import com.erudika.para.utils.Config;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 *
 * @author Alex Bogdanovski <alex@erudika.com>
 */
public class IndexBasedDAOIT extends DAOTest {
	
	@BeforeClass
	public static void setUpClass() {
		System.setProperty("para.env", "embedded");
		// dependency hell?
		dao = new IndexBasedDAO();
		Search search = new ElasticSearch(dao);
		((IndexBasedDAO) dao).setSearch(search);
		ElasticSearchUtils.createIndex(Config.APP_NAME_NS);
		ElasticSearchUtils.createIndex(appName1);
		ElasticSearchUtils.createIndex(appName2);
	}
	
	@AfterClass
	public static void tearDownClass() {
		ElasticSearchUtils.deleteIndex(Config.APP_NAME_NS);
		ElasticSearchUtils.deleteIndex(appName1);
		ElasticSearchUtils.deleteIndex(appName2);
		ElasticSearchUtils.shutdownClient();
	}
	
}