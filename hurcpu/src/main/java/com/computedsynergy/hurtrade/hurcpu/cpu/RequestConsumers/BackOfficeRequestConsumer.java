/*
 * Copyright 2016 Faisal Thaheem <faisal.ajmal@gmail.com>.
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
package com.computedsynergy.hurtrade.hurcpu.cpu.RequestConsumers;

import com.computedsynergy.hurtrade.hurcpu.cpu.ClientRequestProcessor;
import com.computedsynergy.hurtrade.hurcpu.cpu.CommodityUpdateProcessor;
import com.computedsynergy.hurtrade.sharedcomponents.commandline.CommandLineOptions;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.QuoteList;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.positions.Position;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.trade.TradeRequest;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.trade.TradeResponse;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.updates.ClientUpdate;
import com.computedsynergy.hurtrade.sharedcomponents.models.impl.UserModel;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.User;
import com.computedsynergy.hurtrade.sharedcomponents.util.HurUtil;
import com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil;
import com.github.jedis.lock.JedisLock;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil.*;

/**
 *
 * @author Faisal Thaheem <faisal.ajmal@gmail.com>
 */
public class BackOfficeRequestConsumer extends DefaultConsumer {

    private final String officeExhcangeName;
    private final String dealerInQueueName;
    private final String dealerOutQueueName;
    private final int officeId;
    private List<String> officeUsers  = new ArrayList<>();

    private Object lockOfficeUsers = new Object(); //governs access to officeUsers
    private Object lockChannelWrite = new Object(); //governs access to write access on mq channel

    private Timer tmrUserKeysUpdateTimer = new Timer(true);
    private Timer tmrOfficePositionsDispatchTimer = new Timer(true);


    private Gson gson = new Gson();

    public BackOfficeRequestConsumer(Channel channel,
                         String officeExchangeName,
                         String dealerInQueueName,
                         String dealerOutQueueName,
                         int officeId
    ) {
        super(channel);
        this.officeExhcangeName = officeExchangeName;
        this.dealerInQueueName = dealerInQueueName;
        this.dealerOutQueueName = dealerOutQueueName;
        this.officeId = officeId;

    }



    @Override
    public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
        super.handleDelivery(consumerTag, envelope, properties, body); //To change body of generated methods, choose Tools | Templates.

        long deliveryTag = envelope.getDeliveryTag();

        TradeResponse response = null;
        boolean responseRequired = true;
        String userid = null;
        User user = null;
        
        try {

            TradeRequest request = TradeRequest.fromJson(new String(body));
            response = new TradeResponse(request);
            
            userid = properties.getUserId();
            if(userid == null){
                responseRequired = false;
                
            }else{
                
                UserModel userModel = new UserModel();
                user = userModel.getByUsername(userid);
                if(user == null){
                    Logger.getLogger(ClientRequestProcessor.class.getName()).log(Level.SEVERE, "Could not resolve user for name: ", userid);
                    responseRequired = false;
                }
                
                try(Jedis jedis = RedisUtil.getInstance().getJedisPool().getResource()){
                    JedisLock lock = new JedisLock(
                                jedis, 
                                RedisUtil.getLockNameForUserProcessing(user.getUseruuid()),
                                RedisUtil.TIMEOUT_LOCK_USER_PROCESSING, 
                                RedisUtil.EXPIRY_LOCK_USER_PROCESSING
                            );

                    try {
                        if(lock.acquire()){

                            //process client's positions
                            processTradeRequest(response, user);
                            lock.release();

                        }else{
                            Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, "Could not lock user for order processing {0}",  user.getUsername());
                        }
                    } catch (InterruptedException ex) {
                        Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }                
            }

        } catch (Exception ex) {
            Logger.getLogger(ClientRequestProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }

        getChannel().basicAck(deliveryTag, false);
        
        if(!responseRequired || null == userid){
            return;
        }
        
        String clientExchangeName = HurUtil.getClientExchangeName(user.getUseruuid());

        ClientUpdate update = new ClientUpdate(response);
        Gson gson = new Gson();
        String serializedResponse = gson.toJson(update);

        try {
            getChannel().basicPublish(clientExchangeName, "response", null, serializedResponse.getBytes());
        } catch (Exception ex) {

            Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private void processTradeRequest(TradeResponse response, User user)
    {
        
        //todo, check if this commodity is allowed to this customer
        Map<String, BigDecimal> userSpread = RedisUtil
                                                .getInstance()
                                                .getUserSpreadMap(RedisUtil.getUserSpreadMapName(user.getUseruuid()));
        
        if(!userSpread.containsKey(response.getRequest().getCommodity())){
            
            response.setResponseCommodityNotAllowed();
            
            Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, 
                    "{0} requested invalid commodity: {1}",new Object[]{ user.getUsername() , response.getRequest().getCommodity()});
            return;
        }
        
        
        //get the last quoted prices for commodities for this user
        String serializedQuotes = RedisUtil.getInstance().getSeriaizedQuotesForClient(user.getUseruuid());
        QuoteList quotesForClient = gson.fromJson(serializedQuotes, QuoteList.class);
        
        if(!quotesForClient.containsKey(response.getRequest().getCommodity()))
        {
            response.setResponseCommodityNotAllowed();
            
            Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, 
                    "{0} requested unknown commodity: {1}",new Object[]{ user.getUsername() , response.getRequest().getCommodity()});
            return;
        }
        
        String userPositionsKeyName = getUserPositionsKeyName(user.getUseruuid());
        Type mapType = new TypeToken<Map<UUID, Position>>(){}.getType();
        
        try(Jedis jedis = RedisUtil.getInstance().getJedisPool().getResource()){
            JedisLock lock = new JedisLock(jedis, getLockNameForUserPositions(userPositionsKeyName), TIMEOUT_LOCK_USER_POSITIONS, EXPIRY_LOCK_USER_POSITIONS);
            try{
                if(lock.acquire()){

                    //get client positions
                    Map<UUID, Position> positions = gson.fromJson(jedis.get(userPositionsKeyName),mapType);
                    Position position = null;

                    try {

                        switch (response.getRequest().getRequestType()) {
                            case TradeRequest.REQUEST_TYPE_BUY: {
                                position = new Position(
                                        UUID.randomUUID(), Position.ORDER_TYPE_BUY,
                                        response.getRequest().getCommodity(),
                                        response.getRequest().getRequestedLot(),
                                        quotesForClient.get(response.getRequest().getCommodity()).ask
                                );
                            }
                            break;
                            case TradeRequest.REQUEST_TYPE_SELL: {
                                position = new Position(
                                        UUID.randomUUID(), Position.ORDER_TYPE_SELL,
                                        response.getRequest().getCommodity(),
                                        response.getRequest().getRequestedLot(),
                                        quotesForClient.get(response.getRequest().getCommodity()).bid
                                );
                            }
                            break;
                        }
                    }catch(Exception ex){
                        Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, ex.getMessage(), ex);
                    }

                    if(null != position){
                        positions.put(position.getOrderId(), position);
                        response.setResposneOk();

                        //set client positions
                        String serializedPositions = gson.toJson(positions);
                        jedis.set(userPositionsKeyName, serializedPositions);
                    }

                    lock.release();

                    //post the newly opened position for dealer review


                }else{
                    Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, null, "Could not process user positions " + user.getUsername());
                }
            }catch(Exception ex){
                Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, null, "Could not process user positions " + user.getUsername());
            }
        }
    }

}