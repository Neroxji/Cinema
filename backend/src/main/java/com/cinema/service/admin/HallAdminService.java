package com.cinema.service.admin;

import com.cinema.common.factory.ImaxSeatFactory;
import com.cinema.common.factory.SeatMatrixFactory;
import com.cinema.common.factory.StandardSeatFactory;
import com.cinema.controller.admin.dto.HallCreateRequest;
import com.cinema.controller.admin.dto.HallResponse;
import com.cinema.model.CinemaHall;
import com.cinema.model.enums.HallType;
import com.cinema.repository.CinemaHallRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端影厅服务（UC102 维护影厅与座位模板）。
 *
 * <p>// @UseCase: UC102/UC103
 *
 * <p>座位模板三要素（rows/cols/zone_rule）一律取自 {@link SeatMatrixFactory} 工厂，
 * 与 DataSeeder 播种同源，保证 hall 声明与 seat.zone 一致；本服务只落模板，
 * 不预生成座位——座位按场次在创建场次（UC103-4）时由工厂生成。
 */
@Service
public class HallAdminService {

    private final CinemaHallRepository cinemaHallRepository;
    private final ImaxSeatFactory imaxSeatFactory;
    private final StandardSeatFactory standardSeatFactory;

    public HallAdminService(CinemaHallRepository cinemaHallRepository,
                            ImaxSeatFactory imaxSeatFactory,
                            StandardSeatFactory standardSeatFactory) {
        this.cinemaHallRepository = cinemaHallRepository;
        this.imaxSeatFactory = imaxSeatFactory;
        this.standardSeatFactory = standardSeatFactory;
    }

    /** 创建影厅与座位模板（UC102）：模板参数由工厂按 hallType 提供。 */
    @Transactional
    public HallResponse create(HallCreateRequest request) {
        SeatMatrixFactory factory = factoryOf(request.hallType());
        CinemaHall hall = new CinemaHall();
        hall.setName(request.name());
        hall.setHallType(factory.hallType());
        hall.setRows(factory.rowCount());
        hall.setCols(factory.colCount());
        hall.setZoneRule(factory.zoneRule());
        CinemaHall saved = cinemaHallRepository.save(hall);
        return new HallResponse(saved.getId(), saved.getName(), saved.getHallType(),
                saved.getRows(), saved.getCols(), saved.getZoneRule(),
                saved.getRows() * saved.getCols());
    }

    private SeatMatrixFactory factoryOf(HallType hallType) {
        return hallType == HallType.IMAX ? imaxSeatFactory : standardSeatFactory;
    }
}
