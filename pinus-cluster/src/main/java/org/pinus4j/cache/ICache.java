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
package org.pinus4j.cache;

/**
 * database cache interface.
 *
 * @author duanbn
 * @since 0.7.1
 */
public interface ICache {

    /**
	 * 获取过期时间
	 * 
	 * @return
	 */
	public int getExpire();

	/**
	 * 销毁对象
	 */
	public void close();

}
