package cn.dm.controller;

import cn.dm.common.Constants;
import cn.dm.common.Dto;
import cn.dm.common.LogUtils;
import cn.dm.service.OrderService;
import cn.dm.vo.CreateOrderVo;
import cn.dm.vo.ManagementOrderVo;
import cn.dm.vo.QueryOrderVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 订单页Controller
 */
@RestController
@RequestMapping(value = "/api/v/")
public class OrderController {

    @Autowired
    private LogUtils logUtils;
    @Autowired
    private OrderService orderService;

    /**
     * 下单
     *
     * @param orderVo
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/createOrder", method = RequestMethod.POST)
    @ResponseBody
    public Dto createOrder(@RequestBody CreateOrderVo orderVo) throws Exception {
        return orderService.createOrder(orderVo);
    }

    /**
     * 根据订单号拆查询订单
     *
     * @param param-orderNo
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/queryOrderByOrderNo", method = RequestMethod.POST)
    @ResponseBody
    public Dto<QueryOrderVo> queryOrderByOrderNo(@RequestBody Map<String, Object> param) throws Exception {
        return orderService.queryOrderByOrderNo((String) param.get("orderNo"));
    }

    /**
     * 监听订单支付状态
     *
     * @param param-orderNo
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/queryOrderState", method = RequestMethod.POST)
    @ResponseBody
    public Dto queryOrderState(@RequestBody Map<String, Object> param) throws Exception {
        return orderService.queryOrderState((String) param.get("orderNo"));
    }


    /**
     * 查询订单列表
     *
     * @param param-orderType
     * @param param-orderTime
     * @param param-keyword
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/queryOrderList", method = RequestMethod.POST)
    @ResponseBody
    public Dto<List<ManagementOrderVo>> queryOrderList(@RequestBody Map<String, Object> param, @RequestHeader String token) throws Exception {
        return orderService.queryOrderList((Integer) param.get("orderType"), (Integer) param.get("orderTime"), (String) param.get("keyword"), token);
    }

    /**
     * 10分钟执行一次 刷新订单的状态 将未支付且超时的订单修改为取消支付的状态
     */
//    @Scheduled(cron = "*0 0/10 * * * ?")
    @Scheduled(cron = "0/10 * *  * * ?")
    public void flushCancelOrderType() {
        try {
            boolean flag = orderService.flushCancelOrderType();
            //修改排期座位表中相应的座位的状态改为有座
            orderService.updateSchedulerSeatStatus();
            logUtils.i(Constants.TOPIC.DEFAULT, flag ? "刷取订单成功" : "刷单失败");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
