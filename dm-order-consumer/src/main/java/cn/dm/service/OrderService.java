package cn.dm.service;

import cn.dm.common.Dto;
import cn.dm.vo.CreateOrderVo;
import cn.dm.vo.ManagementOrderVo;
import cn.dm.vo.QueryOrderVo;

import java.util.List;

public interface OrderService {
    /**
     * 下单接口
     *
     * @param orderVo
     * @return
     * @throws Exception
     */
    public Dto createOrder(CreateOrderVo orderVo) throws Exception;


    /**
     * 根据订单号查询订单
     *
     * @param orderNo
     * @return
     * @throws Exception
     */
    public Dto<QueryOrderVo> queryOrderByOrderNo(String orderNo) throws Exception;


    /**
     * 监听订单状态
     *
     * @param orderNo
     * @return
     * @throws Exception
     */
    public Dto queryOrderState(String orderNo) throws Exception;


    /**
     * 查询订单列表
     *
     * @param orderType
     * @param orderTime
     * @param keyword
     * @return
     * @throws Exception
     */
    public Dto<List<ManagementOrderVo>> queryOrderList(Integer orderType, Integer orderTime, String keyword) throws Exception;
}
