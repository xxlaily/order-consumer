package cn.dm.service.impl;

import cn.dm.client.*;
import cn.dm.common.*;
import cn.dm.exception.OrderErrorCode;
import cn.dm.pojo.*;
import cn.dm.service.OrderService;
import cn.dm.vo.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rabbitmq.client.Channel;
import javafx.collections.ObservableMap;
import org.apache.commons.lang.ArrayUtils;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;


@Component
public class OrderServiceImpl implements OrderService {

    @Autowired
    private RestDmOrderClient restDmOrderClient;

    @Autowired
    private RestDmSchedulerSeatClient restDmSchedulerSeatClient;

    @Autowired
    private RestDmSchedulerSeatPriceClient restDmSchedulerSeatPriceClient;

    @Autowired
    private RestDmItemClient restDmItemClient;

    @Autowired
    private RestDmLinkUserClient restDmLinkUserClient;

    @Autowired
    private RestDmOrderLinkUserClient restDmOrderLinkUserClient;

    @Autowired
    private RedisUtils redisUtils;
    @Resource
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private LogUtils logUtils;

    @Override
    public Dto createOrder(CreateOrderVo orderVo) throws Exception {
        String[] seatArray = orderVo.getSeatPositions().split(",");
        //先查询对应的商品信息，如果没有直接返回错误信息
        DmItem dmItem = restDmItemClient.getDmItemById(orderVo.getItemId());
        checkDataIsNull(dmItem);
        DmSchedulerSeat dmSchedulerSeat = null;
        double totalAmount = 0;
        //生成订单号
        String orderNo = OrderUtils.createOrderNo();
        //座位价格集合
        Double[] doublesPrice = new Double[seatArray.length];
        //先把当前座位对应的剧场锁定,避免同时操作
        while (!redisUtils.lock(String.valueOf(orderVo.getSchedulerId()))) {
            TimeUnit.SECONDS.sleep(3);
        }
        boolean isLock = false;
        //查看当前座位是否已经被占用,如果被占用则直接返回
        for (int i = 0; i < seatArray.length; i++) {
            if (EmptyUtils.isNotEmpty(redisUtils.get(orderVo.getSchedulerId() + ":" + seatArray[i]))) {
                isLock = true;
                break;
            }
        }
        if (isLock) {
            //座位已经被锁定，返回下订单失败
            redisUtils.unlock(String.valueOf(orderVo.getSchedulerId()));
            throw new BaseException(OrderErrorCode.ORDER_SEAT_LOCKED);
        }
        for (int i = 0; i < seatArray.length; i++) {
            //查询每个坐位对应的级别
            String[] seats = seatArray[i].split("_");
            dmSchedulerSeat = restDmSchedulerSeatClient.getDmSchedulerSeatByOrder(orderVo.getSchedulerId(), Integer.parseInt(seats[0]), Integer.parseInt(seats[1]));
            //更新作为状态为锁定待付款
            dmSchedulerSeat.setStatus(Constants.SchedulerSeatStatus.SchedulerSeat_TOPAY);
            //更新下单用户
            dmSchedulerSeat.setUserId(orderVo.getUserId());
            dmSchedulerSeat.setUpdatedTime(new Date());
            //更新订单编号
            dmSchedulerSeat.setOrderNo(orderNo);
            //更新数据库
            restDmSchedulerSeatClient.qdtxModifyDmSchedulerSeat(dmSchedulerSeat);
            //开始计算总价格
            DmSchedulerSeatPrice dmSchedulerSeatPrice = restDmSchedulerSeatPriceClient.getDmSchedulerSeatPriceBySchedulerIdAndArea(dmSchedulerSeat.getAreaLevel(), dmSchedulerSeat.getScheduleId());
            //保存座位价格，后续在完善订单明细表中使用
            doublesPrice[i] = dmSchedulerSeatPrice.getPrice();
            totalAmount += dmSchedulerSeatPrice.getPrice();
        }
        //生成订单数据
        DmOrder dmOrder = new DmOrder();
        dmOrder.setOrderNo(orderNo);
        BeanUtils.copyProperties(orderVo, dmOrder);
        dmOrder.setItemName(dmItem.getItemName());
        dmOrder.setOrderType(Constants.OrderStatus.TOPAY);//未支付
        dmOrder.setTotalCount(seatArray.length);
        if (orderVo.getIsNeedInsurance() == Constants.OrderStatus.ISNEEDINSURANCE_YES) {
            //需要保险，总金额增加保险金额
            totalAmount += Constants.OrderStatus.NEEDINSURANCE_MONEY;
        }
        dmOrder.setTotalAmount(totalAmount);
        dmOrder.setInsuranceAmount(Constants.OrderStatus.NEEDINSURANCE_MONEY);
        dmOrder.setCreatedTime(new Date());
        Long orderId = 0L;
        try {
            orderId = restDmOrderClient.qdtxAddDmOrder(dmOrder);
        } catch (Exception e) {
            //订单创建失败，需要重置锁定的座位信息
            sendResetSeatMsg(dmSchedulerSeat.getScheduleId(), seatArray);
            redisUtils.unlock(String.valueOf(orderVo.getSchedulerId()));
            throw new BaseException(OrderErrorCode.ORDER_NO_DATA);
        }
        //添加下单关联用户
        String[] linkIds = orderVo.getLinkIds().split(",");
        //把所有的关联用户插入数据库中
        for (int i = 0; i < linkIds.length; i++) {
            //先查询对应的用户信息
            DmLinkUser dmLinkUser = restDmLinkUserClient.getDmLinkUserById(Long.parseLong(linkIds[i]));
            if (EmptyUtils.isEmpty(dmLinkUser)) {
                //关联用户不存在，需要重置座位信息
                sendResetSeatMsg(dmSchedulerSeat.getScheduleId(), seatArray);
                //订单创建无法添加关联人，需要删除掉之前的订单
                sendDelOrderMsg(orderId);
                //重置订单明细关联人信息
                sendResetLinkUser(orderId);
                redisUtils.unlock(String.valueOf(orderVo.getSchedulerId()));
                throw new BaseException(OrderErrorCode.ORDER_NO_DATA);
            }
            DmOrderLinkUser dmOrderLinkUser = new DmOrderLinkUser();
            dmOrderLinkUser.setOrderId(orderId);
            dmOrderLinkUser.setLinkUserId(dmLinkUser.getId());
            dmOrderLinkUser.setLinkUserName(dmLinkUser.getName());
            dmOrderLinkUser.setX(Integer.parseInt(seatArray[i].split("_")[0]));
            dmOrderLinkUser.setY(Integer.parseInt(seatArray[i].split("_")[1]));
            dmOrderLinkUser.setCreatedTime(new Date());
            dmOrderLinkUser.setPrice(doublesPrice[i]);
            //插入数据
            try {
                restDmOrderLinkUserClient.qdtxAddDmOrderLinkUser(dmOrderLinkUser);
            } catch (Exception e) {
                e.printStackTrace();
                //发送消息重置所有
                sendResetSeatMsg(dmSchedulerSeat.getScheduleId(), seatArray);
                sendDelOrderMsg(orderId);
                //重置订单明细关联人信息
                sendResetLinkUser(orderId);
                redisUtils.unlock(String.valueOf(orderVo.getSchedulerId()));
                throw new BaseException(OrderErrorCode.ORDER_NO_DATA);
            }

        }
        //将座位锁定设置为永久
        setSeatLock(orderVo, seatArray);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("orderNo", orderNo);
        return DtoUtil.returnDataSuccess(jsonObject);
    }

    /**
     * 将座位永久锁定,并将剧场锁定删除
     *
     * @param seatArray
     */
    private void setSeatLock(CreateOrderVo orderVo, String[] seatArray) {
        redisUtils.unlock(String.valueOf(orderVo.getSchedulerId()));
        for (int i = 0; i < seatArray.length; i++) {
            redisUtils.set(orderVo.getSchedulerId() + ":" + seatArray[i], "lock");
        }
    }

    @Override
    public Dto<QueryOrderVo> queryOrderByOrderNo(String orderNo) throws Exception {
        //根据订单号获取订单信息
        DmOrder dmOrder = getOrderByOrderNo(orderNo);
        //根据userId和scheduleId获取座位信息
        List<DmOrderLinkUser> dmOrderLinkUserList = getOrderLinkUserListByOrderId(dmOrder);
        checkDataIsNull(dmOrderLinkUserList);
        //根据itemId获取商品名称
        DmItem dmItem = restDmItemClient.getDmItemById(dmOrder.getItemId());
        checkDataIsNull(dmItem);
        //封装返回数据
        QueryOrderVo queryOrderVo = new QueryOrderVo();
        queryOrderVo.setItemName(dmItem.getItemName());
        queryOrderVo.setSeatCount(dmOrder.getTotalCount());
        BeanUtils.copyProperties(dmOrder, queryOrderVo);
        //拼接座位信息
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dmOrderLinkUserList.size(); i++) {
            sb.append(dmOrderLinkUserList.get(i).getX() + "_" + dmOrderLinkUserList.get(i).getY() + ",");
        }
        //去掉最有一个多余的分个号
        queryOrderVo.setSeatName(sb.substring(0, sb.length() - 1));
        return DtoUtil.returnDataSuccess(queryOrderVo);
    }

    @Override
    public Dto queryOrderState(String orderNo) throws Exception {
        //根据订单号获取订单信息
        DmOrder dmOrder = getOrderByOrderNo(orderNo);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("orderNo", dmOrder.getOrderNo());
        jsonObject.put("orderType", dmOrder.getOrderType());
        return DtoUtil.returnDataSuccess(jsonObject);
    }

    @Override
    public Dto<List<ManagementOrderVo>> queryOrderList(Integer orderType, Integer orderTime, String keyword, String token) throws Exception {
        //查询当前用户
        String tokenUser = null;
        if ((tokenUser = (String) redisUtils.get(token)) == null) {
            throw new BaseException(OrderErrorCode.COMMON_NO_LOGIN);
        }
        DmUserVO dmUserVO = JSON.parseObject(tokenUser, DmUserVO.class);
        //查询对应类型/时间/关键字的订单列表
        Map<String, Object> orderMap = new HashMap<String, Object>();
        orderMap.put("userId", dmUserVO.getUserId());
        if (orderType != 3) {
            orderMap.put("orderType", orderType);
        }
        orderMap.put("orderTime", orderTime);
        if (EmptyUtils.isNotEmpty(keyword)) {
            orderMap.put("orderNo", "%" + keyword + "%");
            orderMap.put("itemName", "%" + keyword + "%");
        }
        List<DmOrder> dmOrderList = restDmOrderClient.getDmOrderListByOrderNoOrDate(orderMap);
        List<ManagementOrderVo> managementOrderVoList = new ArrayList<ManagementOrderVo>();
        for (DmOrder dmOrder : dmOrderList) {
            //开始组装返回数据
            ManagementOrderVo managementOrderVo = new ManagementOrderVo();
            BeanUtils.copyProperties(dmOrder, managementOrderVo);
            managementOrderVo.setNum(dmOrder.getTotalCount());
            //拼接订单中的座位信息,根据订单联系人来判断座位，一个座位对应一个联系人
            List<DmOrderLinkUser> dmOrderLinkUserList = getOrderLinkUserListByOrderId(dmOrder);
            checkDataIsNull(dmOrderLinkUserList);
            //拼接商品单价信息，格式为：x1_y1_price,x2_y2_price
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dmOrderLinkUserList.size(); i++) {
                DmOrderLinkUser dmOrderLinkUser = dmOrderLinkUserList.get(i);
                sb.append(dmOrderLinkUser.getX() + "_" + dmOrderLinkUser.getY() + "_" + dmOrderLinkUser.getPrice() + ",");
            }
            //去掉多余的分隔符
            managementOrderVo.setUnitPrice(sb.substring(0, sb.length() - 1));
            managementOrderVo.setSellTime(DateUtil.format(dmOrder.getCreatedTime()));
            //添加到返回数据集合中
            managementOrderVoList.add(managementOrderVo);
        }
        return DtoUtil.returnDataSuccess(managementOrderVoList);
    }


    /**
     * 根据orderId获取座位信息
     *
     * @param dmOrder
     * @return
     * @throws Exception
     */
    public List<DmOrderLinkUser> getOrderLinkUserListByOrderId(DmOrder dmOrder) throws Exception {
        Map<String, Object> seatMap = new HashMap<String, Object>();
        seatMap.put("orderId", dmOrder.getId());
        return restDmOrderLinkUserClient.getDmOrderLinkUserListByMap(seatMap);
    }


    /**
     * 发送需要重置座位状态的消息
     *
     * @param scheduleId
     * @param seatArray
     */
    public void sendResetSeatMsg(Long scheduleId, String[] seatArray) {
        Map<String, Object> resetSeatMap = new HashMap<String, Object>();
        resetSeatMap.put("scheduleId", scheduleId);
        resetSeatMap.put("seats", seatArray);
        logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[sendResetSeatMsg]" + "发送重置座位消息，需要排期为" + scheduleId);
        rabbitTemplate.convertAndSend(Constants.RabbitQueueName.TOPIC_EXCHANGE, Constants.RabbitQueueName.TO_RESET_SEAT_QUQUE, resetSeatMap,new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message message) throws AmqpException {
                //设置消息持久化，避免消息服务器挂了重启之后找不到消息
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }
        });
    }

    /**
     * 发送需要删除订单的消息
     */
    public void sendDelOrderMsg(Long orderId) {
        logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[sendDelOrderMsg]" + "发送重置订单消息，需要重置ID为" + orderId + "的订单");
        rabbitTemplate.convertAndSend(Constants.RabbitQueueName.TOPIC_EXCHANGE, Constants.RabbitQueueName.TO_DEL_ORDER_QUQUE, orderId,new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message message) throws AmqpException {
                //设置消息持久化，避免消息服务器挂了重启之后找不到消息
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }
        });
    }

    /**
     * 发送需要重置关联人的消息
     */
    public void sendResetLinkUser(Long orderId) {
        logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[sendResetLinkUser]" + "发送重置联系人消息，需要重置订单ID为" + orderId + "的联系人");
        rabbitTemplate.convertAndSend(Constants.RabbitQueueName.TOPIC_EXCHANGE, Constants.RabbitQueueName.TO_RESET_LINKUSER_QUQUE, orderId,new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message message) throws AmqpException {
                //设置消息持久化，避免消息服务器挂了重启之后找不到消息
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }
        });
    }

    /**
     * 根据订单号返回具体的订单信息
     *
     * @param orderNo
     * @return
     */
    public DmOrder getOrderByOrderNo(String orderNo) throws Exception {
        //根据订单号查询到具体的订单数据
        Map<String, Object> orderMap = new HashMap<String, Object>();
        orderMap.put("orderNo", orderNo);
        List<DmOrder> dmOrderList = restDmOrderClient.getDmOrderListByMap(orderMap);
        checkDataIsNull(dmOrderList);
        //订单号只能匹配到一个对象数据，直接返回第一个数据
        return dmOrderList.get(0);
    }


    /**
     * 检测数据是否为空
     */
    public void checkDataIsNull(Object object) throws BaseException {
        if (EmptyUtils.isEmpty(object)) {
            throw new BaseException(OrderErrorCode.ORDER_NO_DATA);
        }
    }

    /**
     * 更新订单状态（支付）
     *
     * @param dmItemMessageVo
     * @throws Exception
     */
    @RabbitListener(queues = Constants.RabbitQueueName.TO_UPDATED_ORDER_QUEUE)
    public void updateOrderType(DmItemMessageVo dmItemMessageVo, Message message, Channel channel) {
        logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[updateOrderType]" + "更新订单状态队列，准备更新编号为" + dmItemMessageVo.getOrderNo() + "的订单");
        try {
            //找到对应订单
            DmOrder dmOrder = restDmOrderClient.getDmOrderByOrderNo(dmItemMessageVo.getOrderNo());
            //更新对应的订单状态为支付成功
            dmOrder.setOrderType(Constants.OrderStatus.SUCCESS);
            //更新支付类型
            dmOrder.setPayType(dmItemMessageVo.getPayMethod() + "");
            //更新编号
            if (dmItemMessageVo.getPayMethod() == Constants.PayMethod.WEIXIN) {
                dmOrder.setWxTradeNo(dmItemMessageVo.getTradeNo());
            } else {
                dmOrder.setAliTradeNo(dmItemMessageVo.getTradeNo());
            }
            dmOrder.setUpdatedTime(new Date());
            //更新数据库
            restDmOrderClient.qdtxModifyDmOrder(dmOrder);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                //消费者处理业务出现异常时，将消息放到死亡队列
                channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,false);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[updateOrderType]" + "更新订单状态队列，已更新编号为" + dmItemMessageVo.getOrderNo() + "的订单的支付状态为：" + dmItemMessageVo.getStatus() + ",支付编码为:" + dmItemMessageVo.getTradeNo());
    }


    /**
     * 重置座位状态信息
     *
     * @throws Exception
     */
    @RabbitListener(queues = Constants.RabbitQueueName.TO_RESET_SEAT_QUQUE)
    public void resetSeatMsg(Map<String, Object> resetSeatMap, Message message, Channel channel){
        Long scheduleId = (Long) resetSeatMap.get("scheduleId");
        String[] seatArray = (String[]) resetSeatMap.get("seats");
        try {
            for (int i = 0; i < seatArray.length; i++) {
                //查询每个坐位对应的级别
                String[] seats = seatArray[i].split("_");
                DmSchedulerSeat dmSchedulerSeat = restDmSchedulerSeatClient.getDmSchedulerSeatByOrder(scheduleId, Integer.parseInt(seats[0]), Integer.parseInt(seats[1]));
                logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[resetSeatMsg]" + "重置座位状态队列，准备重置排期为：" + scheduleId + "的第 " + dmSchedulerSeat.getX() + "排,第 " + dmSchedulerSeat.getY() + "列的位置状态为空闲");
                dmSchedulerSeat.setStatus(Constants.SchedulerSeatStatus.SchedulerSeat_FREE);
                dmSchedulerSeat.setOrderNo(null);
                dmSchedulerSeat.setUserId(null);
                restDmSchedulerSeatClient.qdtxModifyDmSchedulerSeat(dmSchedulerSeat);
                logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[resetSeatMsg]" + "重置座位状态队列，已成功重置排期为：" + scheduleId + "的第 " + dmSchedulerSeat.getX() + "排,第 " + dmSchedulerSeat.getY() + "列的位置状态为空闲");
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                //消费者处理业务出现异常时，将消息放到死亡队列
                channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,false);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }


    /**
     * 删除订单
     *
     * @throws Exception
     */
    @RabbitListener(queues = Constants.RabbitQueueName.TO_DEL_ORDER_QUQUE)
    public void delOrderMsg(Long orderId, Message message, Channel channel){
        logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[delOrderMsg]" + "重置订单队列，准备删除编号为" + orderId + "的订单");
        try {
            restDmOrderClient.deleteDmOrderById(orderId);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                //消费者处理业务出现异常时，将消息放到死亡队列
                channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,false);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[delOrderMsg]" + "重置订单队列，已经删除编号为" + orderId + "的订单");
    }

    /**
     * 重置订单明细表关联人信息
     *
     * @throws Exception
     */
    @RabbitListener(queues = Constants.RabbitQueueName.TO_RESET_LINKUSER_QUQUE)
    public void delOrderLinkUserMsg(Long orderId, Message message, Channel channel){
        logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[delOrderLinkUserMsg]" + "重置订单联系人队列，准备删除编号为" + orderId + "的订单");
        try {
            restDmOrderLinkUserClient.deleteDmOrderLinkUserByOrderId(orderId);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                //消费者处理业务出现异常时，将消息放到死亡队列
                channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,false);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
        logUtils.i(Constants.TOPIC.ORDER_CONSUMER, "[delOrderLinkUserMsg]" + "重置订单联系人队列，已经删除编号为" + orderId + "的订单");
    }

    /**
     * 刷新订单状态，对于未支付的订单，超过两个小时则修改其状态为取消支付
     *
     * @throws Exception
     */
    @Override
    public boolean flushCancelOrderType() throws Exception {
        return restDmOrderClient.flushCancelOrderType();
    }

    @Override
    public List<DmOrder> getDmOrderByOrderTypeAndTime() throws Exception {
        return restDmOrderClient.getDmOrderByOrderTypeAndTime();
    }

    @Override
    public boolean updateSchedulerSeatStatus() throws Exception {
        boolean flag = false;
        List<DmOrder> dmOrders = this.getDmOrderByOrderTypeAndTime();
        for (DmOrder dmOrder : dmOrders) {
            Map schedulerMap = new HashMap();
            schedulerMap.put("orderNo", dmOrder.getOrderNo());
            List<DmSchedulerSeat> dmSchedulerSeats = restDmSchedulerSeatClient.getDmSchedulerSeatListByMap(schedulerMap);
            for (DmSchedulerSeat dmSchedulerSeat : dmSchedulerSeats) {
                dmSchedulerSeat.setStatus(1);
                dmSchedulerSeat.setOrderNo(null);
                dmSchedulerSeat.setUserId(null);
                flag = restDmSchedulerSeatClient.qdtxModifyDmSchedulerSeat(dmSchedulerSeat) > 0 ? true : false;
                //删除之前订单座位永久锁
                redisUtils.delete(dmSchedulerSeat.getScheduleId() + ":" + dmSchedulerSeat.getX() + "_" + dmSchedulerSeat.getY());
            }
        }
        return flag;
    }
}
