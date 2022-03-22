/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.plugin.apache.dubbo.proxy;

import cn.hutool.core.util.URLUtil;
import com.sigma.common.base.exception.BusinessException;
import com.sigma.common.base.result.H5Result;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.service.GenericException;
import org.apache.dubbo.rpc.service.GenericService;
import org.apache.shenyu.common.constant.Constants;
import org.apache.shenyu.common.dto.MetaData;
import org.apache.shenyu.common.enums.ResultEnum;
import org.apache.shenyu.common.exception.ShenyuException;
import org.apache.shenyu.common.utils.ParamCheckUtils;
import org.apache.shenyu.plugin.apache.dubbo.cache.ApacheDubboConfigCache;
import org.apache.shenyu.plugin.dubbo.common.param.DubboParamResolveService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * dubbo proxy service is  use GenericService.
 */
public class ApacheDubboProxyService {

    private final DubboParamResolveService dubboParamResolveService;

    /**
     * Instantiates a new Dubbo proxy service.
     *
     * @param dubboParamResolveService the generic param resolve service
     */
    public ApacheDubboProxyService(final DubboParamResolveService dubboParamResolveService) {
        this.dubboParamResolveService = dubboParamResolveService;
    }

    /**
     * Generic invoker object.
     *
     * @param body     the body
     * @param metaData the meta data
     * @param exchange the exchange
     * @return the object
     * @throws ShenyuException the shenyu exception
     */
    public Mono<Object> genericInvoker(final String body, final MetaData metaData, final ServerWebExchange exchange) throws ShenyuException {
        ReferenceConfig<GenericService> reference = ApacheDubboConfigCache.getInstance().get(metaData.getPath());
        if (Objects.isNull(reference) || StringUtils.isEmpty(reference.getInterface())) {
            ApacheDubboConfigCache.getInstance().invalidate(metaData.getPath());
            reference = ApacheDubboConfigCache.getInstance().initRef(metaData);
        }
        GenericService genericService = reference.get();
        Pair<String[], Object[]> pair;
        if (StringUtils.isBlank(metaData.getParameterTypes()) || ParamCheckUtils.dubboBodyIsEmpty(body)) {
            pair = new ImmutablePair<>(new String[]{}, new Object[]{});
        } else {
            pair = dubboParamResolveService.buildParameter(body, metaData.getParameterTypes());
        }
        fillUserLoginInfo2DubboContext(exchange);
        return Mono.fromFuture(invokeAsync(genericService, metaData.getMethodName(), pair.getLeft(), pair.getRight()).thenApply(ret -> {
            if (Objects.isNull(ret)) {
                ret = Constants.DUBBO_RPC_RESULT_EMPTY;
            }
            exchange.getAttributes().put(Constants.RPC_RESULT, H5Result.success(ret));
            exchange.getAttributes().put(Constants.CLIENT_RESPONSE_RESULT_TYPE, ResultEnum.SUCCESS.getName());
            return ret;
        })).onErrorMap(exception -> {
            if (exception instanceof BusinessException) {
                return exception;
            } else if (exception instanceof GenericException) {
                return new ShenyuException(((GenericException) exception).getExceptionMessage());
            } else {
                return new ShenyuException(exception);
            }
        });
    }
    
    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> invokeAsync(final GenericService genericService, final String method, final String[] parameterTypes, final Object[] args) throws GenericException {
        //Compatible with asynchronous calls of lower Dubbo versions
        genericService.$invoke(method, parameterTypes, args);
        //Object resultFromFuture = RpcContext.getContext().getFuture();
        Object resultFromFuture = RpcContext.getServiceContext().getFuture();
        return resultFromFuture instanceof CompletableFuture ? (CompletableFuture<Object>) resultFromFuture : CompletableFuture.completedFuture(resultFromFuture);
    }

    @SuppressWarnings("unchecked")
    private void fillUserLoginInfo2DubboContext(final ServerWebExchange exchange) {
        HttpHeaders httpHeaders = exchange.getRequest().getHeaders();
        Optional.ofNullable(URLUtil.encode(httpHeaders.getFirst("customerId"))).ifPresent(customerId -> {
            RpcContext.getClientAttachment().setAttachment("sassCustomerId", customerId);
        });
        Optional.ofNullable(URLUtil.encode(httpHeaders.getFirst("userId"))).ifPresent(userId -> {
            RpcContext.getClientAttachment().setAttachment("sassUserId", userId);
        });
        Optional.ofNullable(URLUtil.encode(httpHeaders.getFirst("orgId"))).ifPresent(orgId -> {
            RpcContext.getClientAttachment().setAttachment("sassOrgId", orgId);
        });
        Optional.ofNullable(URLUtil.encode(httpHeaders.getFirst("userRole"))).ifPresent(userRole -> {
            RpcContext.getClientAttachment().setAttachment("userType", userRole);
        });
        Optional.ofNullable(URLUtil.encode(httpHeaders.getFirst("accessToken"))).ifPresent(accessToken -> {
            RpcContext.getClientAttachment().setAttachment("access-token", accessToken);
        });
    }
}
