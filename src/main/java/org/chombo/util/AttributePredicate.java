/*
 * chombo: Hadoop Map Reduce utility
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.chombo.util;

import java.util.Map;

/**
 * @author pranab
 *
 */
public abstract class AttributePredicate {
	protected int attribute;
	protected String operator;
	protected Map<String, Object> context;

	public static final String GREATER_THAN = "gt";
	public static final String LESS_THAN = "lt";
	public static final String EQUAL_TO = "eq";
	public static final String IN = "in";
	public static final String NOT_IN = "ni";
	public static final String PREDICATE_SEP = "\\s+";
	public static final String DATA_TYPE_SEP = ":";
	public static final String VALUE_LIST_SEP = "\\|";
	
	public AttributePredicate() {
	}
	
	public AttributePredicate(int attribute, String operator) {
		super();
		this.attribute = attribute;
		this.operator = operator;
	}
	
	public abstract void build(int attribute, String operator, String value);

	/**
	 * @param context
	 * @return
	 */
	public AttributePredicate withContext(Map<String, Object> context) {
		this.context = context;
		return this;
	}

	
	/**
	 * evaluates predicate 
	 * @param record
	 * @return
	 */
	public abstract boolean evaluate(String[] record);
	
}
