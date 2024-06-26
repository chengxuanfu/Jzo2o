package com.jzo2o.market.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.common.expcetions.BadRequestException;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.model.PageResult;
import com.jzo2o.common.utils.*;
import com.jzo2o.market.constants.RedisConstants;
import com.jzo2o.market.constants.TabTypeConstants;
import com.jzo2o.market.enums.ActivityStatusEnum;
import com.jzo2o.market.mapper.ActivityMapper;
import com.jzo2o.market.model.domain.Activity;
import com.jzo2o.market.model.dto.request.ActivityQueryForPageReqDTO;
import com.jzo2o.market.model.dto.request.ActivitySaveReqDTO;
import com.jzo2o.market.model.dto.response.ActivityInfoResDTO;
import com.jzo2o.market.model.dto.response.SeizeCouponInfoResDTO;
import com.jzo2o.market.service.IActivityService;
import com.jzo2o.market.service.ICouponService;
import com.jzo2o.market.service.ICouponWriteOffService;
import com.jzo2o.mysql.utils.PageUtils;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.ls.LSInput;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.jzo2o.market.constants.RedisConstants.RedisKey.*;
import static com.jzo2o.market.enums.ActivityStatusEnum.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author itcast
 * @since 2023-09-16
 */
@Service
public class ActivityServiceImpl extends ServiceImpl<ActivityMapper, Activity> implements IActivityService {
    private static final int MILLION = 1000000;

    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private ICouponService couponService;

    @Resource
    private ICouponWriteOffService couponWriteOffService;

    @Override
    public PageResult<ActivityInfoResDTO> queryForPage(ActivityQueryForPageReqDTO activityQueryForPageReqDTO) {
        LocalDateTime now = DateUtils.now();
        // 1.查询准备
        LambdaQueryWrapper<Activity> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        // 查询条件
        lambdaQueryWrapper.eq(ObjectUtils.isNotNull(activityQueryForPageReqDTO.getId()), Activity::getId, activityQueryForPageReqDTO.getId())
                .like(StringUtils.isNotEmpty(activityQueryForPageReqDTO.getName()), Activity::getName, activityQueryForPageReqDTO.getName())
                .eq(ObjectUtils.isNotNull(activityQueryForPageReqDTO.getType()), Activity::getType, activityQueryForPageReqDTO.getType())
                .eq(ObjectUtils.isNotNull(activityQueryForPageReqDTO.getStatus()), Activity::getStatus, activityQueryForPageReqDTO.getStatus());

        // 排序
        lambdaQueryWrapper.orderByDesc(Activity::getId);
        // 分页
        Page<Activity> activityPage = new Page<>(activityQueryForPageReqDTO.getPageNo().intValue(), activityQueryForPageReqDTO.getPageSize().intValue());
        activityPage = baseMapper.selectPage(activityPage, lambdaQueryWrapper);
        return PageUtils.toPage(activityPage, ActivityInfoResDTO.class);
    }

    @Override
    public ActivityInfoResDTO queryById(Long id) {
        // 1.获取活动
        Activity activity = baseMapper.selectById(id);
        // 判空
        if (activity == null) {
            return new ActivityInfoResDTO();
        }
        // 2.数据转换，并返回信息
        ActivityInfoResDTO activityInfoResDTO = BeanUtils.toBean(activity, ActivityInfoResDTO.class);
        // 设置状态
//        activityInfoResDTO.setStatus(getStatus(activity.getDistributeStartTime(), activity.getDistributeEndTime(), activity.getStatus()));
        // 3.领取数量
//        Integer receiveNum = couponService.countReceiveNumByActivityId(activity.getId());
        Integer receiveNum = activity.getTotalNum()-activity.getStockNum();
        activityInfoResDTO.setReceiveNum(receiveNum);
        // 4.核销量
        Integer writeOffNum = couponWriteOffService.countByActivityId(id);
        activityInfoResDTO.setWriteOffNum(NumberUtils.null2Zero(writeOffNum));

        //
        return activityInfoResDTO;
    }

    @Override
    public void save(ActivitySaveReqDTO activitySaveReqDTO) {
        // 1.逻辑校验
        activitySaveReqDTO.check();
        // 2.活动数据组装
        // 转换
        Activity activity = BeanUtils.toBean(activitySaveReqDTO, Activity.class);
        // 状态
        activity.setStatus(NO_DISTRIBUTE.getStatus());
        //库存
        activity.setStockNum(activitySaveReqDTO.getTotalNum());
        if(activitySaveReqDTO.getId() == null) {
            activity.setId(IdUtils.getSnowflakeNextId());
        }
        //排序字段
//        long sortBy = DateUtils.toEpochMilli(activity.getDistributeStartTime()) * MILLION + activity.getId() % MILLION;
        // 3.保存
        saveOrUpdate(activity);
    }


    @Override
    public void updateStatus() {
        LocalDateTime now = DateUtils.now();
        // 1.更新已经进行中的状态
        lambdaUpdate()
                .set(Activity::getStatus, ActivityStatusEnum.DISTRIBUTING.getStatus())//更新活动状态为进行中
                .eq(Activity::getStatus, NO_DISTRIBUTE.getStatus())//检索待生效的活动
                .le(Activity::getDistributeStartTime, now)//活动开始时间小于等于当前时间
                .gt(Activity::getDistributeEndTime,now)//活动结束时间大于当前时间
                .update();
        // 2.更新已经结束的
        lambdaUpdate()
                .set(Activity::getStatus, LOSE_EFFICACY.getStatus())//更新活动状态为已失效
                .in(Activity::getStatus, Arrays.asList(DISTRIBUTING.getStatus(), NO_DISTRIBUTE.getStatus()))//检索待生效及进行中的活动
                .lt(Activity::getDistributeEndTime, now)//活动结束时间小于当前时间
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long id) {
        // 1.活动作废
        boolean update = lambdaUpdate()
                .set(Activity::getStatus, ActivityStatusEnum.VOIDED.getStatus())
                .eq(Activity::getId, id)
                .in(Activity::getStatus, Arrays.asList(NO_DISTRIBUTE.getStatus(), DISTRIBUTING.getStatus()))
                .update();
        if(!update) {
            return;
        }
        // 2.未使用优惠券作废
        couponService.revoke(id);

    }

    /**
     * 活动预热
     */
    @Override
    public void preHeat() {
        //当前时间
        LocalDateTime now = DateUtils.now();

        //查询进行中还未到结束的优惠券活动， 1个月内待开始的优惠券活动
        List<Activity> list = lambdaQuery()
                .le(Activity::getDistributeStartTime, now.plusDays(30))
                .in(Activity::getStatus, Arrays.asList(DISTRIBUTING.getStatus(), NO_DISTRIBUTE.getStatus()))
                .orderByAsc(Activity::getDistributeStartTime)
                .list();

        //防止缓存穿透
        if (CollUtils.isEmpty(list)) {
            list = new ArrayList<>();
        }

        // 同步库存
        // 将待生效的活动库写入缓存
        list.stream().filter(v -> getStatus(v.getDistributeStartTime(), v.getDistributeEndTime(), v.getStatus()) == NO_DISTRIBUTE.getStatus()).forEach(v -> {
            redisTemplate.opsForHash().put(String.format(COUPON_RESOURCE_STOCK, v.getId() % 10), v.getId(), v.getTotalNum());
        });
        // 对于已生效的活动库存没有同步时再进行同步
        list.stream().filter(v -> getStatus(v.getDistributeStartTime(), v.getDistributeEndTime(), v.getStatus()) == DISTRIBUTING.getStatus()).forEach(v -> {
            redisTemplate.opsForHash().putIfAbsent(String.format(COUPON_RESOURCE_STOCK, v.getId() % 10), v.getId(), v.getStockNum());
        });

        // 2.数据转换 SeizeCouponInfoResDTO
        List<SeizeCouponInfoResDTO> seizeCouponInfoResDTOList = BeanUtils.copyToList(list, SeizeCouponInfoResDTO.class);
        // 3.活动列表写入缓存
        String seizeCouponInStr = JSONUtil.toJsonStr(seizeCouponInfoResDTOList);
        redisTemplate.opsForValue().set(ACTIVITY_CACHE_LIST, seizeCouponInStr);
    }

    /**
     * 用户端抢券列表分页查询活动信息
     *
     * @param tabType 页面类型
     * @return
     */
    @Override
    public List<SeizeCouponInfoResDTO> queryForListFromCache(Integer tabType) {
        //从 redis 查询活动信息
        Object seizeCouponInfoStr = redisTemplate.opsForValue().get(ACTIVITY_CACHE_LIST);
        //将 json 转为 List
        List<SeizeCouponInfoResDTO> list = JsonUtils.toList(seizeCouponInfoStr.toString(), SeizeCouponInfoResDTO.class);
        // 根据 tabType 来确定查询状态
        int queryStatus = tabType == TabTypeConstants.SEIZING ? DISTRIBUTING.getStatus() : NO_DISTRIBUTE.getStatus();
        //过滤数据，并设置剩余数量、实际状态
        List<SeizeCouponInfoResDTO> collect = list.stream().filter(item -> queryStatus == getStatus(item.getDistributeStartTime(), item.getDistributeEndTime(), item.getStatus()))
                .peek(item -> {
                    item.setRemainNum(item.getStockNum());
                    item.setStatus(queryStatus);
                }).collect(Collectors.toList());
        return collect;
    }

    @Override
    public ActivityInfoResDTO getActivityInfoByIdFromCache(Long id) {
        // 从缓存中获取活动信息
        Object activityList = redisTemplate.opsForValue().get(ACTIVITY_CACHE_LIST);
        if (ObjectUtils.isNull(activityList)) {
            return null;
        }
        // 2. 过滤指定活动信息
        List<ActivityInfoResDTO> list = JSONUtil.toList(activityList.toString(), ActivityInfoResDTO.class);
        if(CollUtils.isEmpty(list)) {
            return null;
        }
        // 3. 过滤指定活动
        return list.stream()
                .filter(activityInfoResDTO -> activityInfoResDTO.getId().equals(id))
                .findFirst().orElse(null);
    }


    /**
     * 扣减库存
     * @param id 活动id
     *  如果扣减库存失败抛出异常
     */
    @Override
    public void deductStock(Long id) {
        boolean update = lambdaUpdate()
                .setSql("stock_num = stock_num - 1")
                .eq(Activity::getId, id)
                .gt(Activity::getStockNum, 0)
                .update();

        if (!update) {
            throw new CommonException("扣减库存失败，活动id为: {}" + id);
        }
    }

    /**
     * 获取状态，
     * 用于xxl或其他定时任务在高性能要求下无法做到实时状态
     *
     * @return
     */
    private int getStatus(LocalDateTime distributeStartTime, LocalDateTime distributeEndTime, Integer status) {
        LocalDateTime now = DateUtils.now();
        if(status == NO_DISTRIBUTE.getStatus() && distributeStartTime.isBefore(now) &&
        distributeEndTime.isAfter(now)) {
            return DISTRIBUTING.getStatus();
        } else if (status == NO_DISTRIBUTE.getStatus() && distributeEndTime.isBefore(now)) {
            return LOSE_EFFICACY.getStatus();
        } else if (status == DISTRIBUTING.getStatus() && distributeEndTime.isBefore(now)) {
            return LOSE_EFFICACY.getStatus();
        }
        return status;
    }
}