package com.jcbbooking.service.impl;

import com.jcbbooking.dto.MenuResponse;
import com.jcbbooking.model.Menu;
import com.jcbbooking.model.Role;
import com.jcbbooking.model.RoleMenuAccess;
import com.jcbbooking.model.RolePermissionAccess;
import com.jcbbooking.repository.MenuRepository;
import com.jcbbooking.repository.RoleMenuAccessRepository;
import com.jcbbooking.repository.RolePermissionAccessRepository;
import com.jcbbooking.service.MenuService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class MenuServiceImpl implements MenuService {

    private final RoleMenuAccessRepository roleMenuAccessRepository;
    private final RolePermissionAccessRepository rolePermissionAccessRepository;
    private final MenuRepository menuRepository;

    @Override
    public List<MenuResponse> getMenusForRole(Role role) {
        log.info("Fetching menus for role: {}", role);

        List<Menu> accessibleMenus;
        if (role == Role.ADMIN) {
            accessibleMenus = menuRepository.findByActiveTrueOrderByDisplayOrderAsc();
        } else {
            List<RoleMenuAccess> accessList = roleMenuAccessRepository.findByRole(role);
            accessibleMenus = accessList.stream()
                    .map(RoleMenuAccess::getMenu)
                    .filter(Menu::getActive)
                    .collect(Collectors.toList());
        }

        // Create a fast map of Menu ID -> MenuResponse
        Map<Long, MenuResponse> responseMap = new HashMap<>();
        for (Menu menu : accessibleMenus) {
            responseMap.put(menu.getId(), MenuResponse.builder()
                    .id(menu.getId())
                    .menuName(menu.getMenuName())
                    .menuCode(menu.getMenuCode())
                    .routePath(menu.getRoutePath())
                    .icon(menu.getIcon())
                    .displayOrder(menu.getDisplayOrder())
                    .subMenus(new ArrayList<>())
                    .build());
        }

        List<MenuResponse> rootMenus = new ArrayList<>();

        // Loop through and link parent-child relationships
        for (Menu menu : accessibleMenus) {
            MenuResponse currentResponse = responseMap.get(menu.getId());
            Menu parent = menu.getParentMenu();

            if (parent != null && responseMap.containsKey(parent.getId())) {
                // If it has a parent and the parent is accessible, add to the parent's submenu list
                MenuResponse parentResponse = responseMap.get(parent.getId());
                parentResponse.getSubMenus().add(currentResponse);
            } else {
                // If there's no parent, or the parent is not accessible in this role's scope, it acts as a root
                rootMenus.add(currentResponse);
            }
        }

        // Recursively sort menus by display order
        rootMenus.forEach(this::sortSubMenus);
        rootMenus.sort(Comparator.comparingInt(MenuResponse::getDisplayOrder));

        return rootMenus;
    }

    private void sortSubMenus(MenuResponse menuResponse) {
        if (menuResponse.getSubMenus() != null && !menuResponse.getSubMenus().isEmpty()) {
            menuResponse.getSubMenus().sort(Comparator.comparingInt(MenuResponse::getDisplayOrder));
            menuResponse.getSubMenus().forEach(this::sortSubMenus);
        }
    }

    @Override
    public List<String> getPermissionsForRole(Role role) {
        log.info("Fetching permissions for role: {}", role);

        // Load permissions directly from the role_permission_access table
        List<RolePermissionAccess> accessList = rolePermissionAccessRepository.findByRole(role);
        return accessList.stream()
                .map(access -> access.getPermission().getPermissionCode())
                .collect(Collectors.toList());
    }
}
