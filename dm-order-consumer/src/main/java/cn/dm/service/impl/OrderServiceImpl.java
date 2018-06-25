package cn.dm.service.impl;

import cn.dm.client.*;
import cn.dm.common.*;
import cn.dm.exception.OrderErrorCode;
import cn.dm.pojo.*;
import cn.dm.service.OrderService;
import cn.dm.vo.CreateOrderVo;
import cn.dm.vo.DmUserVO;
import cn.dm.vo.ManagementOrderVo;
import cn.dm.vo.QueryOrderVo;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;


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
        for (int i = 0; i < seatArray.length; i++) {
            //查询每个坐位对应的级别
            String[] seats = seatArray[i].split("_");
            dmSchedulerSeat = restDmSchedulerSeatClient.getDmSchedulerSeatByOrder(orderVo.getSchedulerId(), Integer.parseInt(seats[0]), Integer.parseInt(seats[1]));
            //如果当前作为已经被锁定待支付（状态为0）或者已被购买（状态为1），则购买选座异常失败
            if (dmSchedulerSeat.getStatus() == Constants.SchedulerSeatStatus.SchedulerSeat_TOPAY || dmSchedulerSeat.getStatus() == Constants.SchedulerSeatStatus.SchedulerSeat_PAYSUCCESS) {
                throw new BaseException(OrderErrorCode.ORDER_SEAT_LOCKED);
            }
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
        if (orderVo.getIsNeedInsurance() == Constants.OrderStatus.ISNEEDINSURANCE_YES) {
            //需要保险，总金额增加保险金额
            totalAmount += Constants.OrderStatus.NEEDINSURANCE_MONEY;
        }
        dmOrder.setTotalAmount(totalAmount);
        dmOrder.setInsuranceAmount(Constants.OrderStatus.NEEDINSURANCE_MONEY);
        dmOrder.setCreatedTime(new Date());
        Long orderId = restDmOrderClient.qdtxAddDmOrder(dmOrder);
        //添加下单关联用户
        String[] linkIds = orderVo.getLinkIds().split(",");
        //把所有的关联用户插入数据库中
        for (int i = 0; i < linkIds.length; i++) {
            //先查询对应的用户信息
            DmLinkUser dmLinkUser = restDmLinkUserClient.getDmLinkUserById(Long.parseLong(linkIds[i]));
            checkDataIsNull(dmLinkUser);
            DmOrderLinkUser dmOrderLinkUser = new DmOrderLinkUser();
            dmOrderLinkUser.setOrderId(orderId);
            dmOrderLinkUser.setLinkUserId(dmLinkUser.getId());
            dmOrderLinkUser.setLinkUserName(dmLinkUser.getName());
            dmOrderLinkUser.setX(Integer.parseInt(seatArray[i].split("_")[0]));
            dmOrderLinkUser.setY(Integer.parseInt(seatArray[i].split("_")[1]));
            dmOrderLinkUser.setCreatedTime(new Date());
            dmOrderLinkUser.setPrice(doublesPrice[i]);
            //插入数据
            restDmOrderLinkUserClient.qdtxAddDmOrderLinkUser(dmOrderLinkUser);
        }
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("orderNo", orderNo);
        return DtoUtil.returnDataSuccess(jsonObject);
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
}
