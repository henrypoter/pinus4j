/**
 * Copyright 2014 Duan Bingnan
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

package com.pinus.api.enums;

/**
 * Pinus存储中间件支持的数据库类型.
 * 
 * @author duanbn
 */
public enum EnumMode {

	/**
	 * 单机模式.
	 */
	STANDALONE("standalone"),
	/**
	 * 分布式模式. 需要使用zookeeper做分布式协调
	 */
	DISTRIBUTED("distributed");

	/**
	 * 数据库驱动.
	 */
	private String mode;

	private EnumMode(String mode) {
		this.mode = mode;
	}

	/**
	 * 获取驱动.
	 * 
	 * @return 驱动
	 */
	public String getMode() {
		return this.mode;
	}

}
