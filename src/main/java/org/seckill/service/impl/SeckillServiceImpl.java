package org.seckill.service.impl;

import org.apache.commons.collections.MapUtils;
import org.seckill.dao.SeckillDao;
import org.seckill.dao.SuccessSeckillDao;
import org.seckill.dao.cache.RedisDao;
import org.seckill.dto.Exposer;
import org.seckill.dto.SeckillExcution;
import org.seckill.entities.Seckill;
import org.seckill.entities.SuccessKilled;
import org.seckill.enums.SeckillStateEnum;
import org.seckill.exception.RepeatKillException;
import org.seckill.exception.SeckillCloseException;
import org.seckill.exception.SeckillException;
import org.seckill.service.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by yp on 2017/4/22.
 */
//@Component总注解
@Service
public class SeckillServiceImpl implements SeckillService{

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    //注入依赖
    @Autowired
    private SeckillDao seckillDao;

    @Autowired
    private SuccessSeckillDao successSeckillDao;

    @Autowired
    private RedisDao redisDao;

    //用于混淆MD5
    private String slat = "qworibufaiyhwiyr659h53498h";

    public List<Seckill> getSeckillList() {
        return seckillDao.queryAll(0,4);
    }

    public Seckill getById(long seckillId) {
        return seckillDao.queryById(seckillId);
    }

    public Exposer exportSeckillUrl(long seckillId) {
        //优化点：缓存优化
        /**
         * get from cache
         * if null
         *  getfrom db && put cache
         *  else
         *      excute following logic
         *  但是由于这也是属于数据访问，所以以上访问cache也应该放在dao层
         */
        Seckill seckill = null;
        if ((seckill = redisDao.getSeckill(seckillId))==null){
            seckill = seckillDao.queryById(seckillId);
        }

        //System.out.println(seckillId+"/"+getMD5(seckillId));
        Exposer exposer = new Exposer();
        if(seckill == null){
            return new Exposer(false, seckillId);
        }
        Date startTime = seckill.getStartTime();
        Date endTime = seckill.getEndTime();
        Date nowTime = new Date();
        if(nowTime.getTime() < startTime.getTime() || nowTime.getTime()>endTime.getTime()){
            return new Exposer(false, seckillId, nowTime.getTime(), startTime.getTime(), endTime.getTime());
        }
        //md5是字符串转化，用户对md5值修改也无法获取正确的请求
        String md5 = getMD5(seckillId);
        return new Exposer(true, md5, seckillId);
    }

    private String getMD5(long seckillId){
        String base = seckillId + "/" + slat;
        String md5 = DigestUtils.md5DigestAsHex(base.getBytes());
        return md5;
    }

    /**
     * 使用注解控制事务方法的优点：
     * 1.开发团队达成一致约定，明确标注事务方法的编程风格
     * 2.保证事务方法的执行时间尽可能短，不要穿插其他网络操作如RPC/HTTP请求，客玻璃到事务方法外部
     * 3.不是所有的方法都需要事务，如只有一条修改操作、只读操作不需要事务控制
     * @param seckillId
     * @param userPhone
     * @param md5
     * @return
     * @throws SeckillException
     * @throws RepeatKillException
     * @throws SeckillCloseException
     */
    @Transactional
    public SeckillExcution excuteSeckill(long seckillId, long userPhone, String md5) throws SeckillException,
            RepeatKillException, SeckillCloseException {
        //判断用户的请求md5是否有误
        //System.out.println(seckillId+"/"+md5+"\n"+ getMD5(seckillId)+"\n"+md5.equals(getMD5(seckillId)));
        if(md5 == null || !(md5.equals(getMD5(seckillId)))){
            throw new SeckillException("执行操作的连接有误");
        }

        //执行秒杀，减库存
        Date nowTime = new Date();
        //操作数据库时可能出现异常
        try {
            /**
             *
             *  正常逻辑是先减库存再记录购买明细，但由于myslq中减库存的rowlock，会在insert明细后才能释放，
             *  减库存update的，和明细的insert是串行的，没有执行完这个事务都无法释放update的rowlock，导致延时
             *  而减库存有可能存在重复，所以可以事先insert购买明细，筛选无效的秒杀操作，
             *  减少对减库存rowlock的持有，减库存的操作也减少了一半的网络延迟和GC耗时
             */
            //库存大于零,记录购买行为
            int insertCount = successSeckillDao.insertSuccessKilled(seckillId, userPhone);
            if (insertCount <= 0) {
                throw new RepeatKillException("不能进行重复秒杀");
            } else {
                //秒杀成功
                SuccessKilled successKilled = successSeckillDao.queryByIdWithSeckill(seckillId, userPhone);
                /**
                 * 由于返回的状态信息有多种，所以要对转态信息进行封装，推荐使用枚举来封装
                 * 避免使用：return new SeckillExcution(seckillId, 1, "秒杀成功", successKilled);
                 *
                 */
                int updateCount = seckillDao.reduceNumber(seckillId, nowTime);
                //System.out.println(updateCount);
                if (updateCount <= 0) {
                    //库存为零/秒杀结束，时间已过
                    throw new SeckillCloseException("秒杀失败，秒杀结束");
                }
                return new SeckillExcution(seckillId, SeckillStateEnum.SUCCESS, successKilled);
            }
        }
        catch (SeckillCloseException e1){
            throw e1;
        }
        catch (RepeatKillException e2){
            throw e2;
        }
        catch (Exception e){
            logger.error(e.getMessage());
            //把所有检查型异常，转化为运行期异常
            throw new SeckillException("秒杀失败");
        }
        //return null;
    }

    public SeckillExcution excuteSeckillProcedure(long seckillId, long userPhone, String md5) throws SeckillException, RepeatKillException, SeckillCloseException {
        if(md5 == null || !(md5.equals(getMD5(seckillId)))){
            throw new SeckillException("执行操作的连接有误");
        }
        Date killTime = new Date();
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("seckillId", seckillId);
        map.put("phone", userPhone);
        map.put("killTime", killTime);
        map.put("result", null);
        //执行存储过程，result被赋值
        try {
            seckillDao.killByProcedure(map);
            int result = MapUtils.getInteger(map, "result", -2);
            if (result == 1) {
                SuccessKilled sk = successSeckillDao.queryByIdWithSeckill(seckillId, userPhone);
                return new SeckillExcution(seckillId, SeckillStateEnum.SUCCESS, sk);
            }
            else {

                return new SeckillExcution(seckillId, SeckillStateEnum.stateOf(result));
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return new SeckillExcution(seckillId, SeckillStateEnum.INNER_ERROR);
        }
    }
}
