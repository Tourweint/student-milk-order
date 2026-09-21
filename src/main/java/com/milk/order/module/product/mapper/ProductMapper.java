package com.milk.order.module.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.milk.order.module.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 对品种行加行锁（{@code SELECT ... FOR UPDATE}）：把"同品种的发行类操作"排成队。
     *
     * <p><b>为什么需要它：</b>仓库余量的发行封顶（R5′）要先读 W、再插入配额池，
     * 而 W 是台账的聚合读（无锁）——两个并发请求给同品种不同日期设池时，会各自读到旧 W
     * 后双双通过。这是典型的 check-then-act，只能靠一把行锁串行化，不能靠"再查一次"。</p>
     *
     * <p><b>加锁顺序：</b>{@code product → daily_quota}。扣减路径只锁 {@code daily_quota} 行，
     * 因此不会成环。</p>
     */
    @Select("SELECT * FROM product WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Product selectForUpdate(@Param("id") Long id);
}
