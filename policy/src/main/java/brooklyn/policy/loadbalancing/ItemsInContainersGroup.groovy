package brooklyn.policy.loadbalancing;

import java.util.Map

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import brooklyn.entity.Entity
import brooklyn.entity.Group
import brooklyn.entity.basic.AbstractGroup
import brooklyn.entity.basic.DynamicGroup
import brooklyn.event.Sensor
import brooklyn.event.SensorEvent
import brooklyn.event.SensorEventListener
import brooklyn.util.flags.SetFromFlag

/**
 * A group of items that are contained within a given (dynamically changing) set of containers.
 * 
 * The {@link setContainers(Group)} sets the group of containers. The membership of that group
 * is dynamically tracked.
 * 
 * When containers are added/removed, or when an items is added/removed, or when an {@link Moveable} item 
 * is moved then the membership of this group of items is automatically updated accordingly.
 * 
 * For example: in Monterey, this could be used to track the actors that are within a given cluster of venues.
 */
public class ItemsInContainersGroup extends DynamicGroup {

    // TODO Inefficient: will not scale to many 1000s of items

    private static final Logger LOG = LoggerFactory.getLogger(ItemsInContainersGroup)
    
    // FIXME Can't set default value in fiel declaration, because super sets it before this class initializes the field values!
    @SetFromFlag
    Closure itemFilter

    private Group containerGroup
    
    private final SensorEventListener<?> eventHandler = new SensorEventListener<Object>() {
        public void onEvent(SensorEvent<?> event) {
            Entity source = event.getSource()
            Object value = event.getValue()
            Sensor sensor = event.getSensor()
            
            switch (sensor) {
                case AbstractGroup.MEMBER_ADDED:
                    onContainerAdded((Entity) value)
                    break
                case AbstractGroup.MEMBER_REMOVED:
                    onContainerRemoved((Entity) value)
                    break
                case Movable.CONTAINER:
                    onItemMoved(source, (Entity) value)
                    break
                default:
                    throw new IllegalStateException("Unhandled event type $sensor: $event")
            }
        }
    }
    
    public ItemsInContainersGroup(Map props = [:], Entity owner = null) {
        super(props, owner)
        setEntityFilter( {Entity e -> return acceptsEntity(e) } )
        if (itemFilter == null) itemFilter = {true}
    }

    boolean acceptsEntity(Entity e) {
        if (e instanceof Movable) {
            return acceptsItem((Movable)e, ((Movable)e).getAttribute(Movable.CONTAINER))
        }
        return false
    }

    boolean acceptsItem(Movable e, BalanceableContainer c) {
        return (containerGroup != null && c != null) ? itemFilter.call(e) && containerGroup.hasMember((Entity)c) : false
    }

    public void setContainers(Group containerGroup) {
        this.containerGroup = containerGroup
        subscribe(containerGroup, AbstractGroup.MEMBER_ADDED, eventHandler)
        subscribe(containerGroup, AbstractGroup.MEMBER_REMOVED, eventHandler)
        subscribe(null, Movable.CONTAINER, eventHandler)
        
        if (LOG.isTraceEnabled()) LOG.trace("{} scanning entities on container group set", this)
        rescanEntities()
    }
    
    private void onContainerAdded(Entity newContainer) {
        if (LOG.isTraceEnabled()) LOG.trace("{} rescanning entities on container {} added", this, newContainer)
        rescanEntities()
    }
    
    private void onContainerRemoved(Entity oldContainer) {
        if (LOG.isTraceEnabled()) LOG.trace("{} rescanning entities on container {} removed", this, oldContainer)
        rescanEntities()
    }
    
    protected void onEntityAdded(Entity item) {
        if (acceptsEntity(item)) {
            if (LOG.isDebugEnabled()) LOG.debug("{} adding new item {}", this, item)
            addMember(item)
        }
    }
    
    protected void onEntityRemoved(Entity item) {
        if (removeMember(item)) {
            if (LOG.isDebugEnabled()) LOG.debug("{} removing deleted item {}", this, item)
        }
    }
    
    private void onItemMoved(Movable item, BalanceableContainer container) {
        if (LOG.isTraceEnabled()) LOG.trace("{} processing moved item {}, to container {}", this, item, container)
        if (hasMember(item)) {
            if (!acceptsItem(item, container)) {
                if (LOG.isDebugEnabled()) LOG.debug("{} removing moved item {} from group, as new container {} is not a member", this, item, container)
                removeMember(item)
            }
        } else {
            if (acceptsItem(item, container)) {
                if (LOG.isDebugEnabled()) LOG.debug("{} adding moved item {} to group, as new container {} is a member", this, item, container)
                addMember(item)
            }
        }
    }
}