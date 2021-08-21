package com.spin.kill.server.service.impl;

import com.spin.kill.server.config.RedisLockManger;
import com.spin.kill.server.config.RedissonConfig;
import com.spin.kill.server.entity.ItemKill;
import com.spin.kill.server.entity.ItemKillSuccess;
import com.spin.kill.server.enums.SysConstant;
import com.spin.kill.server.mapper.ItemKillMapper;
import com.spin.kill.server.mapper.ItemKillSuccessMapper;
import com.spin.kill.server.service.KillService;
import com.spin.kill.server.service.RabbitSenderService;
import com.spin.kill.server.utils.RandomUtil;
import com.spin.kill.server.utils.SnowFlake;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.joda.time.DateTime;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;


@Service
//@Transactional
public class KillServiceImpl implements KillService{



    private static final Logger log= LoggerFactory.getLogger(KillService.class);

    private SnowFlake snowFlake=new SnowFlake(2,3);

    @Autowired
    private ItemKillSuccessMapper itemKillSuccessMapper;

    @Autowired
    private ItemKillMapper itemKillMapper;

    @Autowired
    private RabbitSenderService rabbitSenderService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;



    /**
     * 商品秒杀核心业务逻辑的处理——高并发测试（未加锁，出现超卖）  yusp
     * @param killId
     * @param userId
     * @return
     * @throws Exception
     */
    @Override
    public Boolean killItem1(Integer killId, Integer userId) throws Exception {
        Boolean result=false;

        //TODO:判断当前用户是否已经抢购过当前商品,测试高并发，一个用户可购买多次
            //TODO:查询待秒杀商品详情
            ItemKill itemKill = itemKillMapper.selectById(killId);

            System.out.println(itemKill);
            //TODO:判断是否可以被秒杀canKill=1? 并且需要剩余数量大于0才可以秒杀
            if (itemKill != null && 1 == itemKill.getCanKill()&&itemKill.getTotal()>0) {
                //TODO:扣减库存-减一
                int res = itemKillMapper.updateKillItem(killId);
                //TODO:扣减是否成功?是-生成秒杀成功的订单，同时通知用户秒杀成功的消息
                if (res > 0) {
                    commonRecordKillSuccessInfo(itemKill,userId);
                    result = true;
                }
            }
        return result;
    }

    /**
     * 商品秒杀核心业务逻辑的处理——高并发测试（未加锁，出现超卖）redis分布式锁优化  yusp
     * 出现一次超卖,当然这里是有问题的，应该把超时和加锁搞到一起搞成原子操作，不然超时设置失败就有可能死锁
     * @param killId
     * @param userId
     * @return
     * @throws Exception
     */
    @Override
    public Boolean killItem2(Integer killId, Integer userId) throws Exception {
        Boolean result=false;
        ValueOperations valueOperations=stringRedisTemplate.opsForValue();
        final String key="redis_lock";
        final String value=RandomUtil.generateOrderCode();//随机值防止删错

        Boolean cacheRes=valueOperations.setIfAbsent(key,value);
        if(cacheRes){
            stringRedisTemplate.expire(key,30,TimeUnit.SECONDS);
            try {
                ItemKill itemKill = itemKillMapper.selectById(killId);
                System.out.println(itemKill);
                if (itemKill != null && 1 == itemKill.getCanKill() && itemKill.getTotal() >=1) {
                    int res = itemKillMapper.updateKillItem(killId);
                    if (res > 0) {
                        commonRecordKillSuccessInfo(itemKill,userId);
                        result = true;
                    }
                }
            } catch (Exception e) {
                throw new Exception("抢购失败，抛出异常~~~redis分布式锁");
            } finally {
                if(value.equals(valueOperations.get(key).toString())){
                    stringRedisTemplate.delete(key);
                }
            }
        }
        return result;
    }

    /**
     * 商品秒杀核心业务逻辑的处理——高并发测试（未加锁，出现超卖）用单机reids模拟redis集群，（因为我在实习写过一个redis集群分布式锁管理器，所以来试试）
     * redisCluster分布式锁优化  yusp
     * 出现一次超卖,这里加锁和设置超时时间也不是原子操作也是有问题的
     * @param killId
     * @param userId
     * @return
     * @throws Exception
     */
    @Autowired
    private RedisLockManger redisLock;
    @Override
    public Boolean killItem3(Integer killId, Integer userId) throws Exception {
        Boolean result=false;
        final String key="statusCheck";
        final String randValue= RandomUtil.generateOrderCode();
        try {
            if(redisLock.tryLock(key,randValue)){
                //业务核心
                ItemKill itemKill = itemKillMapper.selectById(killId);
                System.out.println(itemKill);
                if (itemKill != null && 1 == itemKill.getCanKill() && itemKill.getTotal() >=1) {
                    int res = itemKillMapper.updateKillItem(killId);
                    if (res > 0) {
                        result = true;
                    }
                }
            }
        }catch (Exception e){
            log.error("抢购失败，抛出异常~~~redis集群分布式锁");
        }finally {
            if(randValue.equals(redisLock.getValue(key))) {
                redisLock.unLock(key);
            }
        }
        return result;
    }


    /**
     * 商品秒杀核心业务逻辑的处理——高并发测试（未加锁，出现超卖）加redission锁 yusp
     * 原子操作设置加锁和超时时间
     * @param killId
     * @param userId
     * @return
     * @throws Exception
     */
    @Autowired
    private RedissonClient redissonClient;
    @Override
    public Boolean killItem4(Integer killId, Integer userId) throws Exception {
        Boolean result=false;
        RLock rlock = redissonClient.getLock("statusCheck");
        try {
//            rlock.lock(10,TimeUnit.SECONDS);
            boolean cacheRes = rlock.tryLock(30, 10, TimeUnit.SECONDS);
            if(cacheRes){
            //业务逻辑
                ItemKill itemKill = itemKillMapper.selectById(killId);
                System.out.println(itemKill);
                if (itemKill != null && 1 == itemKill.getCanKill() && itemKill.getTotal() > 0) {
                    int res = itemKillMapper.updateKillItem(killId);
                    if (res > 0) {
                        commonRecordKillSuccessInfo(itemKill, userId);
                        result = true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            rlock.unlock();
            //lock.forceUnlock();
        }
        return result;
    }


    /**
     * 商品秒杀核心业务逻辑的处理-基于ZooKeeper的分布式锁
     * @param killId
     * @param userId
     * @return
     * @throws Exception
     */
    @Autowired
    private CuratorFramework curatorFramework;
    private static final String pathPrefix="/kill/zkLock/";
    @Override
    public Boolean killItem5(Integer killId, Integer userId) throws Exception {
        Boolean result=false;
        InterProcessMutex mutex=new InterProcessMutex(curatorFramework,pathPrefix+killId+userId+"-lock");
        try {
            if (mutex.acquire(10L,TimeUnit.SECONDS)){
                //TODO:核心业务逻辑
                ItemKill itemKill=itemKillMapper.selectById(killId);
                System.out.println(itemKill);
                if (itemKill!=null && 1==itemKill.getCanKill() && itemKill.getTotal()>0){
                    int res=itemKillMapper.updateKillItem(killId);
                    if (res>0){
                        commonRecordKillSuccessInfo(itemKill,userId);
                        result=true;
                    }
                }
            }
        }catch (Exception e){
            throw new Exception("还没到抢购日期、已过了抢购时间或已被抢购完毕！");
        }finally {
            if (mutex!=null){
                mutex.release();
            }
        }
        return result;
    }


    /**
     * 通用的方法-记录用户秒杀成功后生成的订单-并进行异步邮件消息的通知
     * @param kill
     * @param userId
     * @throws Exception
     */
    private void commonRecordKillSuccessInfo(ItemKill kill,Integer userId) throws Exception{
        //TODO:记录抢购成功后生成的秒杀订单记录

        ItemKillSuccess entity=new ItemKillSuccess();
        String orderNo=String.valueOf(snowFlake.nextId());

        //entity.setCode(RandomUtil.generateOrderCode());   //传统时间戳+N位随机数
        entity.setCode(orderNo); //雪花算法  //注掉雪花算法只是为了清晰的看出秒杀的总数
        entity.setItemId(kill.getItemId());
        System.out.println(kill.getItemId());
        entity.setKillId(kill.getId());
        entity.setUserId(userId.toString());
        entity.setStatus(SysConstant.OrderStatus.SuccessNotPayed.getCode().byteValue());
        entity.setCreateTime(DateTime.now().toDate());
        //TODO:学以致用，举一反三 -> 仿照单例模式的双重检验锁写法
//        if (itemKillSuccessMapper.countByKillUserId(kill.getId(),userId) <= 0){
            int res=itemKillSuccessMapper.insert(entity);

            if (res>0){
//                //TODO:进行异步邮件消息的通知=rabbitmq+mail
//                rabbitSenderService.sendKillSuccessEmailMsg(orderNo);
//
//                //TODO:入死信队列，用于 “失效” 超过指定的TTL时间时仍然未支付的订单
//                rabbitSenderService.sendKillSuccessOrderExpireMsg(orderNo);
            }
//        }
    }


//
//    /**
//     * 商品秒杀核心业务逻辑的处理-mysql的优化
//     * @param killId
//     * @param userId
//     * @return
//     * @throws Exception
//     */
//    @Override
//    public Boolean killItemV2(Integer killId, Integer userId) throws Exception {
//        Boolean result=false;
//
//        //TODO:判断当前用户是否已经抢购过当前商品
//        if (itemKillSuccessMapper.countByKillUserId(killId,userId) <= 0){
//            //TODO:A.查询待秒杀商品详情
//            ItemKill itemKill=itemKillMapper.selectByIdV2(killId);
//
//            //TODO:判断是否可以被秒杀canKill=1?
//            if (itemKill!=null && 1==itemKill.getCanKill() && itemKill.getTotal()>0){
//                //TODO:B.扣减库存-减一
//                int res=itemKillMapper.updateKillItemV2(killId);
//
//                //TODO:扣减是否成功?是-生成秒杀成功的订单，同时通知用户秒杀成功的消息
//                if (res>0){
//                    commonRecordKillSuccessInfo(itemKill,userId);
//
//                    result=true;
//                }
//            }
//        }else{
//            throw new Exception("您已经抢购过该商品了!");
//        }
//        return result;
//    }
//
//
//
//    @Autowired
//    private StringRedisTemplate stringRedisTemplate;
//
//
//    /**
//     * 商品秒杀核心业务逻辑的处理-redis的分布式锁
//     * @param killId
//     * @param userId
//     * @return
//     * @throws Exception
//     */
//    @Override
//    public Boolean killItemV3(Integer killId, Integer userId) throws Exception {
//        Boolean result=false;
//
//        if (itemKillSuccessMapper.countByKillUserId(killId,userId) <= 0){
//
//            //TODO:借助Redis的原子操作实现分布式锁-对共享操作-资源进行控制
//            ValueOperations valueOperations=stringRedisTemplate.opsForValue();
//            final String key=new StringBuffer().append(killId).append(userId).append("-RedisLock").toString();
//            final String value=RandomUtil.generateOrderCode();
//            Boolean cacheRes=valueOperations.setIfAbsent(key,value); //luna脚本提供“分布式锁服务”，就可以写在一起
//            //TOOD:redis部署节点宕机了
//            if (cacheRes){
//                stringRedisTemplate.expire(key,30, TimeUnit.SECONDS);
//
//                try {
//                    ItemKill itemKill=itemKillMapper.selectByIdV2(killId);
//                    if (itemKill!=null && 1==itemKill.getCanKill() && itemKill.getTotal()>0){
//                        int res=itemKillMapper.updateKillItemV2(killId);
//                        if (res>0){
//                            commonRecordKillSuccessInfo(itemKill,userId);
//
//                            result=true;
//                        }
//                    }
//                }catch (Exception e){
//                    throw new Exception("还没到抢购日期、已过了抢购时间或已被抢购完毕！");
//                }finally {
//                    if (value.equals(valueOperations.get(key).toString())){
//                        stringRedisTemplate.delete(key);
//                    }
//                }
//            }
//        }else{
//            throw new Exception("Redis-您已经抢购过该商品了!");
//        }
//        return result;
//    }
//
//
//
//
//    @Autowired
//    private RedissonClient redissonClient;
//
//    /**
//     * 商品秒杀核心业务逻辑的处理-redisson的分布式锁
//     * @param killId
//     * @param userId
//     * @return
//     * @throws Exception
//     */
//    @Override
//    public Boolean killItemV4(Integer killId, Integer userId) throws Exception {
//        Boolean result=false;
//
//        final String lockKey=new StringBuffer().append(killId).append(userId).append("-RedissonLock").toString();
//        RLock lock=redissonClient.getLock(lockKey);
//
//        try {
//            Boolean cacheRes=lock.tryLock(30,10,TimeUnit.SECONDS);
//            if (cacheRes){
//                //TODO:核心业务逻辑的处理
//                if (itemKillSuccessMapper.countByKillUserId(killId,userId) <= 0){
//                    ItemKill itemKill=itemKillMapper.selectByIdV2(killId);
//                    if (itemKill!=null && 1==itemKill.getCanKill() && itemKill.getTotal()>0){
//                        int res=itemKillMapper.updateKillItemV2(killId);
//                        if (res>0){
//                            commonRecordKillSuccessInfo(itemKill,userId);
//
//                            result=true;
//                        }
//                    }
//                }else{
//                    throw new Exception("redisson-您已经抢购过该商品了!");
//                }
//            }
//        }finally {
//            lock.unlock();
//            //lock.forceUnlock();
//        }
//        return result;
//    }
//
//
//
//    @Autowired
//    private CuratorFramework curatorFramework;
//
//    private static final String pathPrefix="/kill/zkLock/";
//
//    /**
//     * 商品秒杀核心业务逻辑的处理-基于ZooKeeper的分布式锁
//     * @param killId
//     * @param userId
//     * @return
//     * @throws Exception
//     */
//    @Override
//    public Boolean killItemV5(Integer killId, Integer userId) throws Exception {
//        Boolean result=false;
//
//        InterProcessMutex mutex=new InterProcessMutex(curatorFramework,pathPrefix+killId+userId+"-lock");
//        try {
//            if (mutex.acquire(10L,TimeUnit.SECONDS)){
//
//                //TODO:核心业务逻辑
//                if (itemKillSuccessMapper.countByKillUserId(killId,userId) <= 0){
//                    ItemKill itemKill=itemKillMapper.selectByIdV2(killId);
//                    if (itemKill!=null && 1==itemKill.getCanKill() && itemKill.getTotal()>0){
//                        int res=itemKillMapper.updateKillItemV2(killId);
//                        if (res>0){
//                            commonRecordKillSuccessInfo(itemKill,userId);
//                            result=true;
//                        }
//                    }
//                }else{
//                    throw new Exception("zookeeper-您已经抢购过该商品了!");
//                }
//            }
//        }catch (Exception e){
//            throw new Exception("还没到抢购日期、已过了抢购时间或已被抢购完毕！");
//        }finally {
//            if (mutex!=null){
//                mutex.release();
//            }
//        }
//
//
//
//
//        return result;
//    }
}
