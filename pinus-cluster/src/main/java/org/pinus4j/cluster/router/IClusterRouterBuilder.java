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

package org.pinus4j.cluster.router;

import org.pinus4j.cluster.enums.HashAlgoEnum;

/**
 * for build cluster router intance.
 *
 * @author duanbn
 * @since 1.0.0
 */
public interface IClusterRouterBuilder {

    /**
     * set hash algo.
     */
    public void setHashAlgo(HashAlgoEnum hashAlgo);

    /**
     * get hash algo.
     */
    public HashAlgoEnum getHashAlgo();

	/**
	 * build router instance by give cluster name.
     *
     * @param clusterName cluster name
	 * 
	 * @return
	 */
	public IClusterRouter build(String clusterName);

}
